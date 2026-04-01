import java.io.File
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
            )
        }

        return TypedAst(sourceRoot = sourceRoot.absolutePath, files = fileAsts)
    } finally {
        Disposer.dispose(disposable)
    }
}
