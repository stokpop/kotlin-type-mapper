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
import org.junit.jupiter.api.Test
import java.io.File

class AnalyzerRegressionTest {

    private val memoryCheckSrc: File =
        File("../test-projects/memory-check/src/main/kotlin").canonicalFile

    /** Regression: sourceDir passed as relative path must not cause relativeTo() to throw. */
    @Test
    fun `analyze with relative source path does not throw`() {
        val relativeDir = File("../test-projects/memory-check/src/main/kotlin")
        // canonicalFile mirrors what the CLI does via absoluteFile
        val ast = analyzeKotlinProject(relativeDir.canonicalFile)
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
        val ast = analyzeKotlinProject(memoryCheckSrc)
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
        val ast = analyzeKotlinProject(memoryCheckSrc)
        val javaImpl  = ast.implementorsOf("java.lang.Exception").map { it.fqName }.toSet()
        val kotlinImpl = ast.implementorsOf("kotlin.Exception").map { it.fqName }.toSet()
        assertTrue(javaImpl.isNotEmpty(), "Expected results for java.lang.Exception")
        assertTrue(
            javaImpl == kotlinImpl,
            "java.lang.Exception and kotlin.Exception should return same implementors.\n" +
            "java: $javaImpl\nkotlin: $kotlinImpl"
        )
    }
}
