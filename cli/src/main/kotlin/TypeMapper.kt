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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    val targetDir = if (args.isNotEmpty()) File(args[0])
                    else File(System.getProperty("user.dir")).let { cwd ->
                        // Gradle sets working dir to subproject dir; walk up to find root
                        val candidate = cwd.resolve("test-projects/memory-check/src/main/kotlin")
                        if (candidate.exists()) candidate
                        else cwd.parentFile?.resolve("test-projects/memory-check/src/main/kotlin") ?: candidate
                    }

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

