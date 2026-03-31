import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

fun parseUsingCompilerAPI(file: File): Map<String, Any> {
    val configuration = CompilerConfiguration()
    configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, object : MessageCollector {
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            println("Kotlin Compiler: [$severity] $message")
        }
        override fun hasErrors(): Boolean = false
    })

    val disposable = Disposer.newDisposable()
    val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)

    val ktFile: KtFile = environment.getPsiForFile(file)
    val bindingContext: BindingContext = analyzeKtFile(ktFile, environment)
    Disposer.dispose(disposable)

    // Extract any specific "fully qualified types"
 
   return analyzeTypes(bindingContext:Functions)
}