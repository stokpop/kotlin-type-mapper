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

import nl.stokpop.typemapper.model.*

import java.io.File
import java.security.MessageDigest
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

/** Normalizes CRLF and bare CR to LF. K1's DocumentImpl rejects any CR characters. */
private fun String.normalizeLf(): String = replace("\r\n", "\n").replace("\r", "\n")

private data class NamedSource(val relativePath: String, val content: String, val contentHash: String)

/**
 * Convenience overload: discovers all `.kt` files under [sourceRoot] and analyses them.
 * Use the two-parameter overload when you need to analyse a specific subset of files.
 */
fun analyzeKotlinProject(sourceRoot: File, extraClasspath: List<File> = emptyList()): TypedAst {
    val files = sourceRoot.walkTopDown()
        .filter { it.extension == "kt" }
        .sortedBy { it.absolutePath }
        .toList()
    return analyzeKotlinProject(files, sourceRoot, extraClasspath)
}

/**
 * Runs semantic analysis on all [files] under [sourceRoot] using the Kotlin K1 compiler
 * pipeline, returning a [TypedAst]. See [analyzeNamedSources] for full documentation.
 */
fun analyzeKotlinProject(files: List<File>, sourceRoot: File, extraClasspath: List<File> = emptyList()): TypedAst {
    val namedSources = files.map { file ->
        val content = file.readText().normalizeLf()
        NamedSource(
            relativePath = file.relativeTo(sourceRoot).path,
            content = content,
            contentHash = sha256(content.toByteArray(Charsets.UTF_8)),
        )
    }
    return analyzeNamedSources(namedSources, sourceRoot.absolutePath, extraClasspath)
}

/**
 * Analyses Kotlin source code provided entirely in memory as a map of relative file name to
 * source content (e.g. `mapOf("Foo.kt" to "class Foo")`). No files are written to disk.
 * Content is LF-normalized automatically before being passed to the K1 compiler.
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
    return analyzeNamedSources(namedSources, "", extraClasspath)
}

/**
 * Core K1 analysis pipeline shared by [analyzeKotlinProject] and [analyzeKotlinSources].
 *
 * Note: this uses the **K1 analysis API** (programmatic compiler internals), not the
 * language version. We compile with Kotlin 2.x (K2 compiler) but intentionally use the
 * K1 analysis pipeline here because the K2 Analysis API (KaSession) is a significantly
 * different programming model. The K1 API is deprecated but still present in
 * kotlin-compiler-embeddable 2.x; migration can happen independently.
 *
 * All files are analysed in a single pass so that cross-file type references resolve
 * correctly. [extraClasspath] may contain dependency jars and/or compiled class directories.
 */
@OptIn(K1Deprecation::class, org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR") // K1 API deprecated at ERROR level in Kotlin 2.3+
private fun analyzeNamedSources(namedSources: List<NamedSource>, sourceRootPath: String, extraClasspath: List<File>): TypedAst {
    val configuration = CompilerConfiguration()
    configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    configuration.put(CommonConfigurationKeys.MODULE_NAME, "typemapper")
    configuration.put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_21)
    configuration.put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))
    configuration.configureJdkClasspathRoots()

    val stdlibJar = Unit::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
    if (stdlibJar != null && stdlibJar.exists()) configuration.addJvmClasspathRoots(listOf(stdlibJar))
    if (extraClasspath.isNotEmpty()) configuration.addJvmClasspathRoots(extraClasspath)

    val disposable = Disposer.newDisposable()
    try {
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

        val factory = KtPsiFactory(environment.project, false)
        val ktFiles = namedSources.map { factory.createPhysicalFile(it.relativePath, it.content.normalizeLf()) }

        val analysisResult: AnalysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project, ktFiles,
            CliBindingTrace(environment.project), configuration,
            environment::createPackagePartProvider
        )
        val bindingContext = analysisResult.bindingContext
        val moduleDescriptor = analysisResult.moduleDescriptor

        val fileAsts = namedSources.zip(ktFiles).map { (src, ktFile) ->
            val imports = ktFile.importDirectives
                .filter { !it.isAllUnder }
                .mapNotNull { it.importedFqName?.asString() }
            FileAst(
                relativePath = src.relativePath,
                packageFqName = ktFile.packageFqName.asString(),
                declarations = extractDeclarations(ktFile, bindingContext),
                calls = extractCallSites(ktFile, bindingContext),
                unresolvedReferences = extractUnresolvedReferences(ktFile, bindingContext, moduleDescriptor),
                contentHash = src.contentHash,
                imports = imports,
            )
        }

        // Collect every type FQN that appears as a receiver, declaration, return type, property
        // type, or parameter type, then build the type hierarchy via reflection so queries can
        // walk supertypes (e.g. typeIs('java.util.Collection') matches List/Set return types).
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
        val classLoader = buildClassLoader(
            listOfNotNull(stdlibJar) + extraClasspath
        )
        val reflectionHierarchy = buildTypeHierarchy(seedTypes, classLoader)

        // Build a source-derived hierarchy from K1 analysis results (available without compiled
        // classes). This captures user-defined class supertypes (e.g. "class Foo : Serializable")
        // even when Foo is not yet compiled. The reflection hierarchy takes priority on conflict.
        val sourceHierarchy = mutableMapOf<String, List<String>>()
        for (fileAst in fileAsts) {
            for (decl in fileAst.declarations) {
                if (decl.isClassLike() && decl.superTypes.isNotEmpty()) {
                    sourceHierarchy[rawTypeName(decl.fqName)] = decl.superTypes
                }
            }
        }
        val typeHierarchy = sourceHierarchy + reflectionHierarchy   // reflection wins on conflict

        return TypedAst(sourceRoot = sourceRootPath, files = fileAsts, typeHierarchy = typeHierarchy)
    } finally {
        Disposer.dispose(disposable)
    }
}

/**
 * Extracts unresolved references from the binding context diagnostics for a single [KtFile].
 * These are names (types, variables, functions) that the K1 compiler could not resolve,
 * typically because the dependency is missing from the classpath.
 */
fun extractUnresolvedReferences(
    ktFile: KtFile,
    bindingContext: BindingContext,
    moduleDescriptor: ModuleDescriptor,
): List<UnresolvedReferenceAst> {
    val doc = ktFile.viewProvider.document
    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    val result = mutableListOf<UnresolvedReferenceAst>()

    for (diagnostic in bindingContext.diagnostics) {
        if (diagnostic.psiFile != ktFile) continue
        if (diagnostic.factory != Errors.UNRESOLVED_REFERENCE) continue
        val psiElement = diagnostic.psiElement
        val offset = psiElement.textRange.startOffset
        result.add(
            UnresolvedReferenceAst(
                name = psiElement.text,
                line = lineOf(offset),
                column = colOf(offset),
            )
        )
    }

    // Kotlin compiler never emits UNRESOLVED_REFERENCE for wildcard imports (import x.*).
    // Use the ModuleDescriptor to check whether each star-imported package actually exists.
    for (import in ktFile.importDirectives) {
        if (!import.isAllUnder) continue
        val fqName = import.importedFqName ?: continue
        val pkg = moduleDescriptor.getPackage(FqName(fqName.asString()))
        if (!packageExistsOnClasspath(pkg)) {
            val offset = import.textRange.startOffset
            result.add(
                UnresolvedReferenceAst(
                    name = "${fqName.asString()}.*",
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }
    }

    return result
}

/**
 * Returns true if [pkg] exists on the classpath, i.e. it contains at least one classifier
 * (class, interface, or object).
 *
 * [ModuleDescriptor.getPackage] never returns null — it always produces a [PackageViewDescriptor],
 * even for packages that are not present on the classpath. Querying its member scope for
 * contributed classifiers is the only reliable way to distinguish a real package from a phantom one.
 */
private fun packageExistsOnClasspath(pkg: org.jetbrains.kotlin.descriptors.PackageViewDescriptor): Boolean =
    pkg.memberScope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS).isNotEmpty()

private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
