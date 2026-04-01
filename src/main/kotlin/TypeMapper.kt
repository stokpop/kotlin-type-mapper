import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

// ── JSON data model ───────────────────────────────────────────────────────────

@Serializable
data class ParameterAst(
    val name: String,
    val type: String,
)

@Serializable
data class DeclarationAst(
    val kind: String,                          // "function" | "property"
    val name: String,
    val fqName: String,
    val containingDeclaration: String,
    val returnType: String? = null,            // function only
    val type: String? = null,                  // property only
    val parameters: List<ParameterAst> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class FileAst(
    val relativePath: String,
    val packageFqName: String,
    val declarations: List<DeclarationAst>,
)

@Serializable
data class TypedAst(
    val schemaVersion: String = "1.0",
    val generatedBy: String = "kotlin-TypeMapper",
    val sourceRoot: String,
    val files: List<FileAst>,
)

// ── Type rendering ────────────────────────────────────────────────────────────

fun KotlinType.toFqString(): String {
    val descriptor = constructor.declarationDescriptor
        ?: return toString()                        // error / unresolved type
    val fqn = descriptor.fqNameSafe.asString()
    val nullable = if (isMarkedNullable) "?" else ""
    if (arguments.isEmpty()) return "$fqn$nullable"
    val args = arguments.joinToString(", ") {
        if (it.isStarProjection) "*" else it.type.toFqString()
    }
    return "$fqn<$args>$nullable"
}

// ── PSI traversal ─────────────────────────────────────────────────────────────

fun extractDeclarations(ktFile: KtFile, bindingContext: BindingContext): List<DeclarationAst> {
    val declarations = mutableListOf<DeclarationAst>()
    val doc = ktFile.viewProvider.document

    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    ktFile.accept(object : KtTreeVisitorVoid() {

        override fun visitEnumEntry(enumEntry: KtEnumEntry) {
            super.visitEnumEntry(enumEntry)
            val descriptor = bindingContext[BindingContext.CLASS, enumEntry] ?: return
            val offset = enumEntry.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "enum_entry",
                    name = enumEntry.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.containingDeclaration.fqNameSafe.asString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitClass(klass: KtClass) {
            super.visitClass(klass)
            if (klass is KtEnumEntry) return   // handled by visitEnumEntry
            val descriptor = bindingContext[BindingContext.CLASS, klass] ?: return
            val offset = klass.textRange.startOffset
            val kind = when {
                klass.isEnum()       -> "enum"
                klass.isInterface()  -> "interface"
                klass.isAnnotation() -> "annotation"
                klass.isData()       -> "data_class"
                klass.isSealed()     -> "sealed_class"
                else                 -> "class"
            }
            declarations.add(
                DeclarationAst(
                    kind = kind,
                    name = klass.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
            super.visitObjectDeclaration(declaration)
            val descriptor = bindingContext[BindingContext.CLASS, declaration] ?: return
            val offset = declaration.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = if (declaration.isCompanion()) "companion_object" else "object",
                    name = declaration.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // Primary constructor val/var parameters become class properties;
        // lambda { x: Foo -> ... } explicitly typed parameters also captured here.
        override fun visitParameter(parameter: KtParameter) {
            super.visitParameter(parameter)
            val offset = parameter.textRange.startOffset
            when {
                parameter.hasValOrVar() -> {
                    val descriptor = bindingContext[BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, parameter] ?: return
                    declarations.add(DeclarationAst(
                        kind = "property",
                        name = parameter.name ?: "<anonymous>",
                        fqName = descriptor.fqNameSafe.asString(),
                        containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                        type = descriptor.type.toFqString(),
                        line = lineOf(offset), column = colOf(offset),
                    ))
                }
                parameter.typeReference != null && parameter.parent?.parent is KtFunctionLiteral -> {
                    val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, parameter] ?: return
                    declarations.add(DeclarationAst(
                        kind = "lambda_parameter",
                        name = parameter.name ?: "<anonymous>",
                        fqName = descriptor.fqNameSafe.asString(),
                        containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                        type = descriptor.type.toFqString(),
                        line = lineOf(offset), column = colOf(offset),
                    ))
                }
                // for-loop and catch params handled by visitForExpression / visitCatchSection;
                // named function params already included in the function's parameters list.
            }
        }
        override fun visitForExpression(expression: KtForExpression) {
            super.visitForExpression(expression)
            val param = expression.loopParameter ?: return
            val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, param] ?: return
            val offset = param.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "for_loop_variable",
                    name = param.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // catch (e: IOException) — exception variable
        override fun visitCatchSection(catchClause: KtCatchClause) {
            super.visitCatchSection(catchClause)
            val param = catchClause.catchParameter ?: return
            val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, param] ?: return
            val offset = param.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "catch_variable",
                    name = param.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // val (a, b) = pair — destructuring entries
        override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
            super.visitDestructuringDeclarationEntry(entry)
            val descriptor = bindingContext[BindingContext.VARIABLE, entry] ?: return
            val offset = entry.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "destructured_variable",
                    name = entry.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // typealias Foo = Bar<Baz>
        override fun visitTypeAlias(typeAlias: KtTypeAlias) {
            super.visitTypeAlias(typeAlias)
            val descriptor = bindingContext[BindingContext.TYPE_ALIAS, typeAlias] ?: return
            val offset = typeAlias.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "typealias",
                    name = typeAlias.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.expandedType.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // secondary constructor(x: Foo, y: Bar)
        override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
            super.visitSecondaryConstructor(constructor)
            val descriptor = bindingContext[BindingContext.CONSTRUCTOR, constructor] ?: return
            val offset = constructor.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "constructor",
                    name = constructor.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    returnType = descriptor.returnType.toFqString(),
                    parameters = descriptor.valueParameters.map { p ->
                        ParameterAst(name = p.name.asString(), type = p.type.toFqString())
                    },
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            super.visitNamedFunction(function)
            val descriptor = bindingContext[BindingContext.FUNCTION, function] ?: return
            val offset = function.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "function",
                    name = function.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    returnType = descriptor.returnType?.toFqString() ?: "?",
                    parameters = descriptor.valueParameters.map { p ->
                        ParameterAst(name = p.name.asString(), type = p.type.toFqString())
                    },
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitProperty(property: KtProperty) {
            super.visitProperty(property)
            val descriptor = bindingContext[BindingContext.VARIABLE, property] ?: return
            val offset = property.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "property",
                    name = property.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }
    })

    return declarations
}

// ── Classpath resolution ──────────────────────────────────────────────────────

fun resolveProjectClasspath(projectRoot: File): List<File> = when {
    File(projectRoot, "build.gradle.kts").exists() || File(projectRoot, "build.gradle").exists() ->
        resolveGradleClasspath(projectRoot)
    File(projectRoot, "pom.xml").exists() ->
        resolveMavenClasspath(projectRoot)
    else -> emptyList()
}

private fun resolveGradleClasspath(projectRoot: File): List<File> {
    val initScript = File.createTempFile("typemapper-init", ".gradle.kts").apply {
        deleteOnExit()
        writeText("""
            allprojects {
                afterEvaluate {
                    tasks.register("typeMapperPrintClasspath") {
                        doLast {
                            val cp = configurations.findByName("runtimeClasspath") ?: return@doLast
                            println("TYPEMAPPER_CP:" + cp.resolvedConfiguration.resolvedArtifacts
                                .joinToString(File.pathSeparator) { it.file.absolutePath })
                        }
                    }
                }
            }
        """.trimIndent())
    }
    val gradlew = if (File(projectRoot, "gradlew").exists()) "./gradlew" else "gradle"
    val output = runCommand(projectRoot, gradlew, "-q", "typeMapperPrintClasspath",
        "--init-script", initScript.absolutePath)
    return parseClasspathOutput(output, "TYPEMAPPER_CP:")
}

private fun resolveMavenClasspath(projectRoot: File): List<File> {
    val outputFile = File.createTempFile("typemapper-cp", ".txt").apply { deleteOnExit() }
    runCommand(projectRoot, "mvn", "-q", "dependency:build-classpath",
        "-Dmdep.outputFile=${outputFile.absolutePath}", "-DincludeScope=runtime")
    return if (outputFile.exists())
        outputFile.readText().trim().split(File.pathSeparator).map(::File).filter { it.exists() }
    else emptyList()
}

private fun runCommand(workDir: File, vararg command: String): String {
    val process = ProcessBuilder(*command).directory(workDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return output
}

private fun parseClasspathOutput(output: String, marker: String): List<File> =
    output.lines()
        .firstOrNull { it.startsWith(marker) }
        ?.removePrefix(marker)
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?.filter { it.exists() }
        ?: emptyList()

fun findProjectRoot(sourceDir: File): File? {
    var dir: File? = sourceDir
    while (dir != null) {
        if (File(dir, "build.gradle.kts").exists() ||
            File(dir, "build.gradle").exists() ||
            File(dir, "pom.xml").exists()) return dir
        dir = dir.parentFile
    }
    return null
}

// ── Analysis ──────────────────────────────────────────────────────────────────

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
            )
        }

        return TypedAst(sourceRoot = sourceRoot.absolutePath, files = fileAsts)
    } finally {
        Disposer.dispose(disposable)
    }
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main(args: Array<String>) {
    val targetDir = if (args.isNotEmpty()) File(args[0])
                    else File("test-projects/memory-check/src/main/kotlin")

    val outputFile = if (args.size > 1) File(args[1])
                     else File("typemapper-output.json")

    val kotlinFiles = targetDir.walkTopDown().filter { it.extension == "kt" }.sortedBy { it.name }.toList()

    println("TypeMapper: ${targetDir.absolutePath}")
    println("Found ${kotlinFiles.size} Kotlin source file(s)")

    val projectRoot = findProjectRoot(targetDir)
    val extraClasspath = if (projectRoot != null) {
        println("Resolving classpath from: $projectRoot")
        resolveProjectClasspath(projectRoot).also { println("Classpath: ${it.size} jar(s) resolved") }
    } else {
        println("No build file found; skipping dependency classpath")
        emptyList()
    }

    println("Analysing...")
    val ast = analyzeKotlinProject(kotlinFiles, targetDir, extraClasspath)

    val json = Json { prettyPrint = true }
    outputFile.writeText(json.encodeToString(ast))

    val totalDeclarations = ast.files.sumOf { it.declarations.size }
    println("Written $totalDeclarations declarations across ${ast.files.size} files → ${outputFile.absolutePath}")
}
