import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val totalCalls = ast.files.sumOf { it.calls.size }
    println("Written $totalDeclarations declarations, $totalCalls call sites across ${ast.files.size} files → ${outputFile.absolutePath}")
}

