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
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

fun main(args: Array<String>) {
    TypeMapperCli()
        .subcommands(
            AnalyzeCommand(),
            LoadCommand(),
            QueryCommand().subcommands(
                CallsCommand(),
                CallsPolymorphicCommand(),
                ImplementorsCommand(),
                AnnotatedWithCommand(),
            )
        )
        .main(args)
}

// ---- top-level command ------------------------------------------------------

class TypeMapperCli : CliktCommand("typemapper") {
    override fun help(context: Context) =
        "Analyze Kotlin source files and query the extracted AST."
    override fun run() = Unit
}

// ---- analyze ----------------------------------------------------------------

class AnalyzeCommand : CliktCommand("analyze") {
    override fun help(context: Context) = "Analyze Kotlin sources and emit a JSON AST."

    val sourceDir by argument("SOURCE_DIR", help = "Root directory of Kotlin sources")
    val output    by option("--output", "-o", help = "Write JSON to FILE (default: stdout)")
    val classpath by option("--classpath", "-cp",
        help = "Extra classpath jar (repeatable; default: auto-resolved via Gradle/Maven)").multiple()

    override fun run() {
        val dir = File(sourceDir)
        require(dir.isDirectory) { "SOURCE_DIR does not exist: $sourceDir" }

        val ktFiles = dir.walkTopDown()
            .filter { it.extension == "kt" }
            .sortedBy { it.name }
            .toList()

        echo("Analyzing ${ktFiles.size} Kotlin file(s) in ${dir.absolutePath}", err = true)

        val extraJars: List<File> = if (classpath.isNotEmpty()) {
            classpath.map { File(it) }
        } else {
            val root = findProjectRoot(dir)
            if (root != null) {
                echo("Resolving classpath from: $root", err = true)
                resolveProjectClasspath(root).also {
                    echo("Classpath: ${it.size} jar(s)", err = true)
                }
            } else {
                echo("No build file found; skipping dependency classpath", err = true)
                emptyList()
            }
        }

        val ast = analyzeKotlinProject(ktFiles, dir, extraJars)
        val json = TypedAstJson.toJsonString(ast)

        if (output != null) {
            val out = File(output!!)
            out.parentFile?.mkdirs()
            out.writeText(json)
            echo("Written ${ast.declarations().size} declarations, ${ast.calls().size} call sites → ${out.absolutePath}", err = true)
        } else {
            echo(json)
        }
    }
}

// ---- load -------------------------------------------------------------------

class LoadCommand : CliktCommand("load") {
    override fun help(context: Context) = "Load a JSON AST file and print a summary."

    val file by argument("FILE", help = "Path to JSON AST file")

    override fun run() {
        val ast = TypedAstJson.load(File(file))
        echo("Schema version  : ${ast.schemaVersion}")
        echo("Source root     : ${ast.sourceRoot}")
        echo("Files           : ${ast.files.size}")
        echo("Declarations    : ${ast.declarations().size}")
        echo("Call sites      : ${ast.calls().size}")
        echo("Type hierarchy  : ${ast.typeHierarchy.size} types")
    }
}

// ---- query ------------------------------------------------------------------

class QueryCommand : CliktCommand("query") {
    override fun help(context: Context) =
        "Query a saved JSON AST file. FILE is the path to the JSON output of 'analyze'."

    val file by argument("FILE", help = "Path to JSON AST file")

    override fun run() {
        currentContext.obj = TypedAstJson.load(File(file))
    }
}

class CallsCommand : CliktCommand("calls") {
    override fun help(context: Context) = "Find call sites matching SIG (static-type exact match)."

    val ast by requireObject<TypedAst>()
    val sig by argument("SIG", help = "Signature pattern, e.g. 'kotlin.String#trim()'")
    override fun run() = ast.callsMatchingLocated(sig).forEach { (path, call) -> echo(call.format(path)) }
}

class CallsPolymorphicCommand : CliktCommand("calls-polymorphic") {
    override fun help(context: Context) =
        "Find call sites where the receiver is a subtype of the type in SIG."

    val ast by requireObject<TypedAst>()
    val sig by argument("SIG")
    override fun run() = ast.callsMatchingPolymorphicLocated(sig).forEach { (path, call) -> echo(call.format(path)) }
}

class ImplementorsCommand : CliktCommand("implementors") {
    override fun help(context: Context) =
        "Find class declarations that extend or implement INTERFACE_FQN."

    val ast by requireObject<TypedAst>()
    val fqn by argument("INTERFACE_FQN")
    override fun run() = ast.implementorsOf(fqn).forEach { echo(it.format()) }
}

class AnnotatedWithCommand : CliktCommand("annotated-with") {
    override fun help(context: Context) = "Find declarations carrying ANNOTATION_FQN."

    val ast by requireObject<TypedAst>()
    val fqn by argument("ANNOTATION_FQN")
    override fun run() = ast.declarationsAnnotatedWith(fqn).forEach { echo(it.format()) }
}

// ---- formatting helpers -----------------------------------------------------

fun CallSiteAst.format(filePath: String = ""): String {
    val receiver = dispatchReceiverType ?: extensionReceiverType ?: "<no-receiver>"
    val params = argumentTypes.joinToString(", ")
    val loc = if (filePath.isNotEmpty()) "$filePath:$line:$column" else "$line:$column"
    return "$loc  $receiver → $calleeFqName($params): $returnType"
}

fun DeclarationAst.format(filePath: String = ""): String {
    val typeInfo = returnType ?: type ?: kind
    val loc = if (filePath.isNotEmpty()) "$filePath:$line:$column" else "$line:$column"
    return "$loc  $kind  $fqName  [$typeInfo]"
}
