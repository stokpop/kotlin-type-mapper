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
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Runs semantic analysis on all [files] under [sourceRoot] using the Kotlin K1 compiler
 * pipeline, returning a [TypedAst] with declarations and call sites for every file.
 *
 * All files are analysed in a single pass so that cross-file type references resolve
 * correctly. [extraClasspath] should contain the target project's dependency jars.
 */
@OptIn(K1Deprecation::class)
@Suppress("DEPRECATION")
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
                contentHash = sha256(file),
            )
        }

        return TypedAst(sourceRoot = sourceRoot.absolutePath, files = fileAsts)
    } finally {
        Disposer.dispose(disposable)
    }
}

private fun sha256(file: File): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    return bytes.joinToString("") { "%02x".format(it) }
}
