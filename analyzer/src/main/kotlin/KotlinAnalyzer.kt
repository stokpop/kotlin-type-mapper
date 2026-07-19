/**
 * Copyright (C) 2026 Peter Paul Bakker, Stokpop Software Solutions
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.stokpop.typemapper.analyzer

import com.intellij.openapi.util.Disposer
import nl.stokpop.typemapper.model.FileAst
import nl.stokpop.typemapper.model.TypedAst
import nl.stokpop.typemapper.model.UnresolvedReferenceAst
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.collectDiagnostics
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/** Normalizes CRLF and bare CR to LF. */
private fun String.normalizeLf(): String = replace("\r\n", "\n").replace("\r", "\n")

private data class NamedSource(val relativePath: String, val content: String, val contentHash: String)

fun analyzeKotlinProject(sourceRoot: File, extraClasspath: List<File> = emptyList()): TypedAst {
    val canonicalRoot = sourceRoot.canonicalFile
    val files = canonicalRoot.walkTopDown()
        .filter { it.extension == "kt" }
        .sortedBy { it.absolutePath }
        .toList()
    return analyzeKotlinProject(files, canonicalRoot, extraClasspath)
}

fun analyzeKotlinProject(files: List<File>, sourceRoot: File, extraClasspath: List<File> = emptyList()): TypedAst {
    val canonicalRoot = sourceRoot.canonicalFile
    val namedSources = files.map { file ->
        val canonicalFile = file.canonicalFile
        val content = canonicalFile.readText().normalizeLf()
        NamedSource(
            relativePath = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath,
            content = content,
            contentHash = sha256(content.toByteArray(Charsets.UTF_8)),
        )
    }
    return analyzeNamedSources(
        namedSources = namedSources,
        sourceRoot = canonicalRoot,
        sourceRootPath = canonicalRoot.absolutePath,
        extraClasspath = extraClasspath,
    )
}

/**
 * Analyses an explicit list of Kotlin source [files] on disk without requiring a common source
 * root directory. `FileAst.relativePath` holds each file's absolute path minus the filesystem
 * root prefix (e.g. on Linux/Mac the leading `/` is stripped). Use
 * `TypedAst.resolveAbsolutePath` to reconstruct the full absolute path. All source files must
 * reside on the same filesystem root (always true on Linux/Mac; on Windows all files must be on
 * the same drive).
 *
 * Each file is added to the K2 source module individually, so scattered files are analysed
 * without scanning the whole filesystem root.
 *
 * Use [analyzeKotlinProject] when all files share a known source root and relative paths matter.
 * Use [analyzeKotlinSources] for in-memory / test use cases.
 */
fun analyzeKotlinFileList(files: List<File>, extraClasspath: List<File> = emptyList()): TypedAst {
    val fsRoot = files.firstOrNull()?.canonicalFile?.toPath()?.root?.toString() ?: "/"
    require(files.all { it.canonicalFile.toPath().root.toString() == fsRoot }) {
        "All source files must share the same filesystem root; found: ${files.map { it.canonicalFile.toPath().root }.toSet()}"
    }
    val namedSources = files.map { file ->
        val canonical = file.canonicalFile
        val content = canonical.readText().normalizeLf()
        NamedSource(
            // Strip the filesystem root prefix so resolveAbsolutePath(fsRoot + sep + rel) == canonical path.
            relativePath = canonical.absolutePath.removePrefix(fsRoot),
            content = content,
            contentHash = sha256(content.toByteArray(Charsets.UTF_8)),
        )
    }
    return analyzeNamedSources(
        namedSources = namedSources,
        sourceRoot = File(fsRoot),
        sourceRootPath = fsRoot,
        extraClasspath = extraClasspath,
        explicitSourceFiles = files.map { it.canonicalFile.toPath() },
    )
}

/**
 * Analyses Kotlin source code provided entirely in memory as a map of relative file name to
 * source content (e.g. `mapOf("Foo.kt" to "class Foo")`). No files are written to disk.
 * Content is LF-normalized automatically before analysis.
 */
fun analyzeKotlinSources(sources: Map<String, String>, extraClasspath: List<File> = emptyList()): TypedAst {
    val namedSources = sources.map { (name, content) ->
        val normalizedContent = content.normalizeLf()
        NamedSource(
            relativePath = name,
            content = normalizedContent,
            contentHash = sha256(normalizedContent.toByteArray(Charsets.UTF_8)),
        )
    }

    val tempRoot = createWorkingTempDir()
    try {
        namedSources.forEach { source ->
            val targetFile = tempRoot.resolve(source.relativePath)
            targetFile.parent?.let { Files.createDirectories(it) }
            Files.writeString(targetFile, source.content)
        }
        return analyzeNamedSources(
            namedSources = namedSources,
            sourceRoot = tempRoot.toFile().canonicalFile,
            sourceRootPath = "",
            extraClasspath = extraClasspath,
        )
    } finally {
        tempRoot.toFile().deleteRecursively()
    }
}

private fun analyzeNamedSources(
    namedSources: List<NamedSource>,
    sourceRoot: File,
    sourceRootPath: String,
    extraClasspath: List<File>,
    explicitSourceFiles: List<Path>? = null,
): TypedAst {
    val contentHashes = namedSources.associateBy({ it.relativePath }, { it.contentHash })
    val selectedPaths = contentHashes.keys
    val disposable = Disposer.newDisposable("TypeMapperAnalysis")

    try {
        val stdlibJar = Unit::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                val jdkModule = buildKtSdkModule {
                    addBinaryRootsFromJdkHome(Paths.get(System.getProperty("java.home")), isJre = false)
                    libraryName = "JDK"
                    platform = JvmPlatforms.defaultJvmPlatform
                }

                val stdlibModule = stdlibJar?.takeIf { it.exists() }?.let {
                    buildKtLibraryModule {
                        libraryName = "kotlin-stdlib"
                        addBinaryRoot(it.toPath())
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                }

                val extraModules = extraClasspath.filter { it.exists() }.map { jar ->
                    buildKtLibraryModule {
                        libraryName = jar.nameWithoutExtension
                        addBinaryRoot(jar.toPath())
                        platform = JvmPlatforms.defaultJvmPlatform
                    }
                }

                addModule(buildKtSourceModule {
                    moduleName = "typemapper"
                    platform = JvmPlatforms.defaultJvmPlatform
                    // Add each listed file individually when given an explicit file list (scattered
                    // files with no common dir); otherwise add the single source root directory.
                    if (explicitSourceFiles != null) {
                        explicitSourceFiles.forEach { addSourceRoot(it) }
                    } else {
                        addSourceRoot(sourceRoot.toPath())
                    }
                    addRegularDependency(jdkModule)
                    stdlibModule?.let { addRegularDependency(it) }
                    extraModules.forEach { addRegularDependency(it) }
                })
            }
        }

        val sourceRootPathCanonical = sourceRoot.canonicalFile.invariantSeparatorsPath.trimEnd('/')
        val fileAsts = session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .mapNotNull { ktFile ->
                val relativePath = relativePathOf(ktFile, sourceRootPathCanonical)
                val contentHash = contentHashes[relativePath] ?: return@mapNotNull null
                val imports = ktFile.importDirectives
                    .filter { !it.isAllUnder }
                    .mapNotNull { it.importedFqName?.asString() }
                analyze(ktFile) {
                    FileAst(
                        relativePath = relativePath,
                        packageFqName = ktFile.packageFqName.asString(),
                        declarations = extractDeclarations(ktFile),
                        calls = extractCallSites(ktFile),
                        unresolvedReferences = extractUnresolvedReferences(ktFile),
                        contentHash = contentHash,
                        imports = imports,
                    )
                }
            }
            .sortedBy { it.relativePath }

        // Collect every type FQN that appears as a receiver, return type, or parameter type.
        // This seeds the reflection-based hierarchy so matchesSig can follow supertypes into
        // classes that are not part of the analyzed source tree (stdlib, third-party libs).
        val seedTypes = mutableSetOf<String>()
        for (fileAst in fileAsts) {
            for (call in fileAst.calls) {
                call.dispatchReceiverType?.let { seedTypes.add(rawTypeName(it)) }
                call.extensionReceiverType?.let { seedTypes.add(rawTypeName(it)) }
            }
            for (decl in fileAst.declarations) {
                if (decl.isClassLike()) seedTypes.add(rawTypeName(decl.fqName))
                decl.returnType?.let { seedTypes.add(rawTypeName(it)) }
                decl.type?.let { seedTypes.add(rawTypeName(it)) }
                decl.parameters.forEach { seedTypes.add(rawTypeName(it.type)) }
                decl.superTypes.forEach { seedTypes.add(rawTypeName(it)) }
            }
        }

        val classLoader = buildClassLoader(listOfNotNull(stdlibJar) + extraClasspath)
        val reflectionHierarchy = buildTypeHierarchy(seedTypes, classLoader)
        // Build a source-derived hierarchy from K2 analysis results (available without compiled classes).
        // This covers user-defined classes that may not yet be on the classpath.
        val sourceHierarchy = mutableMapOf<String, List<String>>()
        for (fileAst in fileAsts) {
            for (decl in fileAst.declarations) {
                if (decl.isClassLike() && decl.superTypes.isNotEmpty()) {
                    sourceHierarchy[rawTypeName(decl.fqName)] = decl.superTypes
                }
            }
        }

        return TypedAst(
            sourceRoot = sourceRootPath,
            files = fileAsts,
            // sourceHierarchy + reflectionHierarchy: reflection wins on conflict since it has
            // full supertype chains; source only covers types declared in analyzed files.
            typeHierarchy = sourceHierarchy + reflectionHierarchy,
        )
    } finally {
        Disposer.dispose(disposable)
    }
}

internal fun KaSession.extractUnresolvedReferences(ktFile: KtFile): List<UnresolvedReferenceAst> {
    val doc = ktFile.viewProvider.document
    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    val result = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        .filter { it.factoryName == "UNRESOLVED_REFERENCE" }
        .map { diagnostic ->
            val psiElement = diagnostic.psi ?: return@map null
            val offset = psiElement.textRange.startOffset
            UnresolvedReferenceAst(
                name = psiElement.text,
                line = lineOf(offset),
                column = colOf(offset),
            )
        }
        .filterNotNull()
        .toMutableList()

    for (importDirective in ktFile.importDirectives) {
        if (!importDirective.isAllUnder) continue
        val fqName = importDirective.importedFqName ?: continue
        if (packageLikelyExists(ktFile, fqName.asString())) continue
        val offset = importDirective.textRange.startOffset
        result.add(
            UnresolvedReferenceAst(
                name = "${fqName.asString()}.*",
                line = lineOf(offset),
                column = colOf(offset),
            )
        )
    }

    return result
}

private fun packageLikelyExists(ktFile: KtFile, fqName: String): Boolean {
    if (fqName.startsWith("kotlin.")) return true
    val prefix = "$fqName."
    return ktFile.containingKtFile.declarations.any { declaration ->
        val name = when (declaration) {
            is org.jetbrains.kotlin.psi.KtNamedDeclaration -> declaration.fqName?.asString()
            else -> null
        }
        name?.startsWith(prefix) == true
    }
    // TODO Phase 5: replace this heuristic with a direct K2 package lookup once the stable API is available.
}

private fun relativePathOf(ktFile: KtFile, canonicalSourceRootPath: String): String {
    val fullPath = ktFile.virtualFile.path.replace('\\', '/')
    return fullPath.removePrefix("$canonicalSourceRootPath/")
}

private fun createWorkingTempDir(): Path {
    val parent = File(".").canonicalFile.toPath().resolve(".typemapper-work")
    Files.createDirectories(parent)
    return Files.createTempDirectory(parent, "sources-")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
