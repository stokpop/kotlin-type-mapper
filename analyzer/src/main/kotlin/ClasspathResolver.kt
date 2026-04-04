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

import java.io.File
import java.nio.file.Files

/** Resolves the runtime classpath for a Gradle or Maven project rooted at [projectRoot].
 *  Returns both dependency jars and the project's own compiled class directories. */
fun resolveProjectClasspath(projectRoot: File): List<File> = when {
    File(projectRoot, "settings.gradle.kts").exists() || File(projectRoot, "settings.gradle").exists()
        || File(projectRoot, "build.gradle.kts").exists() || File(projectRoot, "build.gradle").exists() ->
        resolveGradleClasspath(projectRoot) + resolveGradleClassDirs(projectRoot)
    File(projectRoot, "pom.xml").exists() ->
        resolveMavenClasspath(projectRoot) + resolveMavenClassDirs(projectRoot)
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
    // Collect ALL TYPEMAPPER_CP: lines — allprojects emits one per subproject.
    return parseAllClasspathOutput(output, "TYPEMAPPER_CP:")
}

private fun resolveMavenClasspath(projectRoot: File): List<File> {
    // Write a separate .classpath file per module using Maven's ${project.artifactId} property,
    // then collect and merge them. This handles multi-module builds correctly.
    val outputDir = Files.createTempDirectory("typemapper-cp-").toFile().apply { deleteOnExit() }
    runCommand(projectRoot, "mvn", "-q", "dependency:build-classpath",
        "-Dmdep.outputFile=${outputDir.absolutePath}/\${project.artifactId}.classpath",
        "-DincludeScope=runtime")
    return outputDir.listFiles()
        ?.flatMap { f -> f.readText().trim().split(File.pathSeparator).map(::File) }
        ?.filter { it.exists() }
        ?.distinct()
        ?: emptyList()
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

/** Like [parseClasspathOutput] but collects ALL matching lines (for multi-module Gradle). */
private fun parseAllClasspathOutput(output: String, marker: String): List<File> =
    output.lines()
        .filter { it.startsWith(marker) }
        .flatMap { it.removePrefix(marker).split(File.pathSeparator).map(::File) }
        .filter { it.exists() }
        .distinct()

/**
 * Walks up the directory tree from [sourceDir] to find the **root** project.
 *
 * For Gradle, keeps walking up as long as parent directories also contain a build file
 * or settings file, landing at the root where `gradlew` and `settings.gradle[.kts]` live.
 * For Maven, keeps walking up as long as parent directories also contain a `pom.xml`,
 * so that multi-module projects are always resolved from the aggregate root.
 */
fun findProjectRoot(sourceDir: File): File? {
    var dir: File? = sourceDir
    var gradleRoot: File? = null
    var mavenRoot: File? = null
    while (dir != null) {
        when {
            File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()
                || File(dir, "build.gradle.kts").exists() || File(dir, "build.gradle").exists() ->
                gradleRoot = dir   // Gradle: keep going up to find the outermost settings/build file
            File(dir, "pom.xml").exists() ->
                mavenRoot = dir    // Maven: keep going up to find the true aggregate root
        }
        dir = dir.parentFile
    }
    return gradleRoot ?: mavenRoot
}

/**
 * Returns existing Gradle compiled class directories across all modules under [projectRoot].
 * Matches paths of the form: <anything>/build/classes/{kotlin,java,groovy}/main
 */
private fun resolveGradleClassDirs(projectRoot: File): List<File> =
    projectRoot.walkTopDown()
        .onEnter { it.name != ".gradle" && it.name != "src" }
        .filter { dir ->
            dir.isDirectory
                && dir.name == "main"
                && dir.parentFile?.name in setOf("kotlin", "java", "groovy")
                && dir.parentFile?.parentFile?.name == "classes"
                && dir.parentFile?.parentFile?.parentFile?.name == "build"
        }
        .toList()

/**
 * Returns existing Maven compiled class directories for all modules under [projectRoot].
 * Walks the directory tree to find every `target/classes` directory, covering
 * single-module and multi-module projects alike.
 */
private fun resolveMavenClassDirs(projectRoot: File): List<File> =
    projectRoot.walkTopDown()
        .filter { it.isDirectory && it.name == "classes" && it.parentFile?.name == "target" }
        .toList()
