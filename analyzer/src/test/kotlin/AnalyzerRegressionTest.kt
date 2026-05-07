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
import nl.stokpop.typemapper.analyzer.analyzeKotlinProject
import nl.stokpop.typemapper.model.implementorsOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AnalyzerRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createSampleSources(): File {
        val srcRoot = tempDir.resolve("src").toFile().apply { mkdirs() }
        File(srcRoot, "Exceptions.kt").writeText(
            """
            package nl.stokpop.memory

            class MemoryCheckException(message: String) : Exception(message)
            class InvalidHistoLineException : Exception()
            """.trimIndent() + "\n"
        )
        return srcRoot
    }

    /** Regression: sourceDir passed as relative path must not cause relativeTo() to throw. */
    @Test
    fun `analyze with relative source path does not throw`() {
        val srcRoot = createSampleSources().canonicalFile
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        assumeTrue(
            srcRoot.toPath().root == cwd.toPath().root,
            "Skipping: temp dir and CWD are on different drives — relative path not possible"
        )
        val relativePathFromCwd = cwd.toPath().relativize(srcRoot.toPath()).toString()
        val relativeDir = File(relativePathFromCwd)

        val ast = analyzeKotlinProject(relativeDir)
        assertTrue(ast.files.isNotEmpty(), "Expected at least one file in AST")
    }

    /**
     * Regression: implementorsOf("java.lang.Exception") must return classes that extend
     * kotlin.Exception (which is a typealias for java.lang.Exception).
     * Previously this failed because superTypes were rendered as kotlin.Exception instead of
     * the expanded java.lang.Exception.
     */
    @Test
    fun `implementorsOf java Exception finds memory-check exception classes`() {
        val ast = analyzeKotlinProject(createSampleSources())
        val implementors = ast.implementorsOf("java.lang.Exception").map { it.fqName }

        assertTrue(
            "nl.stokpop.memory.MemoryCheckException" in implementors,
            "Expected MemoryCheckException to be an implementor of java.lang.Exception, got: $implementors"
        )
        assertTrue(
            "nl.stokpop.memory.InvalidHistoLineException" in implementors,
            "Expected InvalidHistoLineException to be an implementor of java.lang.Exception, got: $implementors"
        )
    }

    /** kotlin.Exception and java.lang.Exception must be treated as equivalent. */
    @Test
    fun `implementorsOf kotlin Exception finds same classes as java Exception`() {
        val ast = analyzeKotlinProject(createSampleSources())
        val javaImpl  = ast.implementorsOf("java.lang.Exception").map { it.fqName }.toSet()
        val kotlinImpl = ast.implementorsOf("kotlin.Exception").map { it.fqName }.toSet()
        assertTrue(javaImpl.isNotEmpty(), "Expected results for java.lang.Exception")
        assertTrue(
            javaImpl == kotlinImpl,
            "java.lang.Exception and kotlin.Exception should return same implementors.\n" +
            "java: $javaImpl\nkotlin: $kotlinImpl"
        )
    }

    /**
     * A wildcard import from a package that does not exist on the classpath must be reported
     * as an unresolved reference. A valid wildcard import (e.g. kotlin.collections.*) must not.
     */
    @Test
    fun `star import from unknown package is reported as unresolved reference`() {
        val srcRoot = tempDir.resolve("src-star").toFile().apply { mkdirs() }
        File(srcRoot, "StarImports.kt").writeText(
            """
            import kotlin.collections.*
            import com.example.nonexistent.*

            class StarImportTest {
                val list: List<String> = emptyList()
            }
            """.trimIndent() + "\n"
        )

        val ast = analyzeKotlinProject(srcRoot)
        val refs = ast.files.flatMap { it.unresolvedReferences }.map { it.name }

        assertTrue(
            refs.any { it == "com.example.nonexistent.*" },
            "Expected com.example.nonexistent.* to be reported as unresolved, got: $refs"
        )
        assertTrue(
            refs.none { it == "kotlin.collections.*" },
            "Expected kotlin.collections.* NOT to be reported as unresolved, got: $refs"
        )
    }

    /**
     * Regression: return types, property types, and parameter types must be seeded so that
     * hierarchy traversal works even when those types never appear as call receivers.
     * Previously typeIs('java.util.Collection') would fail on a List return type with no call sites.
     */
    @Test
    fun `return type and property type are seeded for hierarchy traversal`() {
        val srcRoot = tempDir.resolve("src-decl-types").toFile().apply { mkdirs() }
        File(srcRoot, "DeclTypes.kt").writeText(
            """
            fun getItems(): List<String> = emptyList()
            fun process(input: Set<Int>) {}
            class Holder { val names: Collection<String> = emptyList() }
            """.trimIndent() + "\n"
        )

        val ast = analyzeKotlinProject(srcRoot)
        val hierarchy = ast.typeHierarchy

        assertTrue(
            hierarchy.containsKey("kotlin.collections.List"),
            "Expected hierarchy entry for kotlin.collections.List (return type), got keys: ${hierarchy.keys}"
        )
        assertTrue(
            hierarchy.containsKey("kotlin.collections.Set"),
            "Expected hierarchy entry for kotlin.collections.Set (param type), got keys: ${hierarchy.keys}"
        )
        assertTrue(
            hierarchy.containsKey("kotlin.collections.Collection"),
            "Expected hierarchy entry for kotlin.collections.Collection (property type), got keys: ${hierarchy.keys}"
        )
    }

    /**
     * Regression: a file with CRLF line endings (as checked out by git on Windows) must be
     * analysed without throwing. K1's DocumentImpl rejects any CR characters.
     */
    @Test
    fun `crlf file on disk is analysed without error`() {
        val srcRoot = tempDir.resolve("src-crlf").toFile().apply { mkdirs() }
        val crlfContent = "package nl.stokpop.memory\r\n\r\nclass CrlfClass\r\n"
        File(srcRoot, "CrlfClass.kt").writeBytes(crlfContent.toByteArray(Charsets.UTF_8))

        val ast = analyzeKotlinProject(srcRoot)

        val declarations = ast.files.flatMap { it.declarations }
        assertTrue(
            declarations.any { it.fqName == "nl.stokpop.memory.CrlfClass" },
            "Expected CrlfClass in declarations even with CRLF file on disk, got: ${declarations.map { it.fqName }}"
        )
    }

    /**
     * Regression: a nullable receiver type (e.g. "kotlin.collections.List?") must be seeded
     * correctly so that hierarchy traversal still finds its supertypes.
     * Previously, the trailing '?' was not stripped before using the type as a map key, so
     * typeHierarchy["kotlin.collections.List?"] was null and BFS returned no supertypes.
     */
    @Test
    fun `nullable receiver type is seeded without trailing question mark`() {
        val srcRoot = tempDir.resolve("src-nullable").toFile().apply { mkdirs() }
        File(srcRoot, "NullableReceiver.kt").writeText(
            """
            fun processItems(items: List<String>?) {
                val empty = items?.isEmpty() ?: true
            }
            """.trimIndent() + "\n"
        )

        val ast = analyzeKotlinProject(srcRoot)
        val hierarchy = ast.typeHierarchy

        val listSupertypes = hierarchy["kotlin.collections.List"]
        assertTrue(
            listSupertypes != null && listSupertypes.isNotEmpty(),
            "Expected type hierarchy entry for kotlin.collections.List from nullable receiver, got: $hierarchy"
        )
    }
}
