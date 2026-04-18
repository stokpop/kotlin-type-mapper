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
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext

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
 * pipeline (KotlinCoreEnvironment / TopDownAnalyzerFacadeForJVM), returning a [TypedAst]
 * with declarations and call sites for every file.
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
@OptIn(K1Deprecation::class) // K1 analysis API (KotlinCoreEnvironment, TopDownAnalyzerFacadeForJVM,
                              // CliBindingTrace) is deprecated in Kotlin 2.x in favour of the K2
                              // Analysis API, but not yet removed. Intentionally kept until a full
                              // migration to KaSession / analyze{} blocks is done.
@Suppress("DEPRECATION") // Suppresses deprecation warnings from the K1 compiler internals used above.
fun analyzeKotlinProject(files: List<File>, sourceRoot: File, extraClasspath: List<File> = emptyList()): TypedAst {
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
        val ktFiles = files.map { factory.createPhysicalFile(it.name, it.readText()) }

        val bindingContext = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
            environment.project, ktFiles,
            CliBindingTrace(environment.project), configuration,
            environment::createPackagePartProvider
        ).bindingContext

        val fileAsts = files.zip(ktFiles).map { (file, ktFile) ->
            FileAst(
                relativePath = file.relativeTo(sourceRoot).path,
                packageFqName = ktFile.packageFqName.asString(),
                declarations = extractDeclarations(ktFile, bindingContext),
                calls = extractCallSites(ktFile, bindingContext),
                unresolvedReferences = extractUnresolvedReferences(ktFile, bindingContext),
                contentHash = sha256(file),
            )
        }

        // Collect every type FQN that appears as a receiver or a declaration class/interface,
        // then build the type hierarchy via reflection so queries can walk supertypes.
        val seedTypes = mutableSetOf<String>()
        for (fileAst in fileAsts) {
            for (call in fileAst.calls) {
                call.dispatchReceiverType?.substringBefore('<')?.let { seedTypes.add(it) }
                call.extensionReceiverType?.substringBefore('<')?.let { seedTypes.add(it) }
            }
            for (decl in fileAst.declarations) {
                if (decl.isClassLike()) seedTypes.add(decl.fqName.substringBefore('<'))
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
                    sourceHierarchy[decl.fqName.substringBefore('<')] = decl.superTypes
                }
            }
        }
        val typeHierarchy = sourceHierarchy + reflectionHierarchy   // reflection wins on conflict

        return TypedAst(sourceRoot = sourceRoot.absolutePath, files = fileAsts, typeHierarchy = typeHierarchy)
    } finally {
        Disposer.dispose(disposable)
    }
}

/**
 * Extracts unresolved references from the binding context diagnostics for a single [KtFile].
 * These are names (types, variables, functions) that the K1 compiler could not resolve,
 * typically because the dependency is missing from the classpath.
 */
fun extractUnresolvedReferences(ktFile: KtFile, bindingContext: BindingContext): List<UnresolvedReferenceAst> {
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
    return result
}

private fun sha256(file: File): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return bytes.joinToString("") { "%02x".format(it) }
}
