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

/**
 * Main entry point for programmatic use of the Kotlin type mapper.
 *
 * Example — in-memory:
 * ```kotlin
 * val ast = KotlinTypeMapper(File("src/main/kotlin")).analyze()
 * val calls = ast.callsMatching("kotlin.String#trim()")
 * ```
 *
 * Example — write JSON then reload later:
 * ```kotlin
 * KotlinTypeMapper(File("src/main/kotlin"), autoResolveClasspath = true)
 *     .analyzeToJson(File("ast.json"))
 *
 * val ast = TypedAstJson.load(File("ast.json"))
 * ```
 *
 * @param sourceDirs One or more directories containing Kotlin source files to analyse.
 * @param classpathJars Explicit dependency jars to pass to the compiler for type resolution.
 *   Ignored when [autoResolveClasspath] is true.
 * @param autoResolveClasspath When true, the classpath is resolved automatically by running
 *   Gradle or Maven in the project containing the source files. Overrides [classpathJars].
 */
class KotlinTypeMapper(
    val sourceDirs: List<File>,
    val classpathJars: List<File> = emptyList(),
    val autoResolveClasspath: Boolean = false,
) {
    constructor(
        sourceDir: File,
        classpathJars: List<File> = emptyList(),
        autoResolveClasspath: Boolean = false,
    ) : this(listOf(sourceDir), classpathJars, autoResolveClasspath)

    /** Analyses all Kotlin files in [sourceDirs] and returns the result in memory. */
    fun analyze(): TypedAst {
        val kotlinFiles = sourceDirs
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .sortedBy { it.name }

        val sourceRoot = commonAncestor(sourceDirs)

        val classpath = when {
            autoResolveClasspath -> {
                val projectRoot = sourceDirs.firstNotNullOfOrNull { findProjectRoot(it) }
                if (projectRoot != null) resolveProjectClasspath(projectRoot) else emptyList()
            }
            else -> classpathJars
        }

        return analyzeKotlinProject(kotlinFiles, sourceRoot, classpath)
    }

    /**
     * Analyses all Kotlin files in [sourceDirs], writes the result to [outputFile] as JSON,
     * and returns the [TypedAst].
     */
    fun analyzeToJson(outputFile: File): TypedAst {
        val ast = analyze()
        TypedAstJson.save(ast, outputFile)
        return ast
    }

    private fun commonAncestor(dirs: List<File>): File {
        if (dirs.size == 1) return dirs.first().canonicalFile
        val parts = dirs.map { it.canonicalFile.absolutePath.split(File.separator) }
        val common = parts.reduce { acc, path ->
            acc.zip(path).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        return File(common.joinToString(File.separator))
    }
}
