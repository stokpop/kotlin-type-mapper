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

/** Resolves the runtime classpath for a Gradle or Maven project rooted at [projectRoot]. */
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

/**
 * Walks up the directory tree from [sourceDir] to find the nearest project root
 * (the first directory containing a `build.gradle.kts`, `build.gradle`, or `pom.xml`).
 */
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
