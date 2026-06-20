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
import nl.stokpop.typemapper.analyzer.analyzeKotlinSources
import nl.stokpop.typemapper.analyzer.analyzeKotlinProject
import nl.stokpop.typemapper.model.implementorsOf
import nl.stokpop.typemapper.model.resolveAbsolutePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AnalyzeKotlinSourcesTest {

    @Test
    fun `sourceRoot is empty string for in-memory analysis`() {
        val ast = analyzeKotlinSources(mapOf("Foo.kt" to "package com.example\nclass Foo"))
        assertEquals("", ast.sourceRoot, "In-memory analysis should produce empty sourceRoot")
    }

    @Test
    fun `resolveAbsolutePath returns null when sourceRoot is empty`() {
        val ast = analyzeKotlinSources(mapOf("Foo.kt" to "package com.example\nclass Foo"))
        val file = ast.files.first()
        assertNull(ast.resolveAbsolutePath(file),
            "resolveAbsolutePath should return null for in-memory AST (no sourceRoot)")
    }

    @Test
    fun `resolveAbsolutePath returns absolute path for file-based analysis`(@TempDir tempDir: File) {
        val src = File(tempDir, "Foo.kt").also { it.writeText("package com.example\nclass Foo") }
        val ast = analyzeKotlinProject(tempDir)
        val file = ast.files.first()
        val resolved = ast.resolveAbsolutePath(file)
        assertNotNull(resolved, "resolveAbsolutePath should return non-null for file-based AST")
        assertEquals(src.canonicalPath, File(resolved!!).canonicalPath,
            "Resolved path should point to the source file")
    }

    @Test
    fun `single in-memory source is analysed`() {
        val sources = mapOf(
            "Foo.kt" to """
                package com.example

                class Foo
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)

        assertEquals(1, ast.files.size, "Expected exactly one file in AST")
        val declarations = ast.files.flatMap { it.declarations }
        assertTrue(
            declarations.any { it.fqName == "com.example.Foo" },
            "Expected com.example.Foo in declarations, got: ${declarations.map { it.fqName }}"
        )
    }

    @Test
    fun `multiple in-memory sources are analysed`() {
        val sources = mapOf(
            "Animal.kt" to """
                package com.example

                open class Animal
            """.trimIndent(),
            "Dog.kt" to """
                package com.example

                class Dog : Animal()
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)

        assertEquals(2, ast.files.size, "Expected two files in AST")
        val implementors = ast.implementorsOf("com.example.Animal").map { it.fqName }
        assertTrue(
            "com.example.Dog" in implementors,
            "Expected Dog to be an implementor of Animal, got: $implementors"
        )
    }

    @Test
    fun `crlf line endings are normalised`() {
        val crlfSource = "package com.example\r\n\r\nclass CrlfFoo\r\n"
        val sources = mapOf("CrlfFoo.kt" to crlfSource)

        val ast = analyzeKotlinSources(sources)

        val declarations = ast.files.flatMap { it.declarations }
        assertTrue(
            declarations.any { it.fqName == "com.example.CrlfFoo" },
            "Expected com.example.CrlfFoo even with CRLF input, got: ${declarations.map { it.fqName }}"
        )
    }

    @Test
    fun `implements java exception is found via in-memory sources`() {
        val sources = mapOf(
            "Exceptions.kt" to """
                package com.example

                class AppException(message: String) : Exception(message)
                class DataException : Exception()
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val implementors = ast.implementorsOf("java.lang.Exception").map { it.fqName }

        assertTrue(
            "com.example.AppException" in implementors,
            "Expected AppException as implementor of java.lang.Exception, got: $implementors"
        )
        assertTrue(
            "com.example.DataException" in implementors,
            "Expected DataException as implementor of java.lang.Exception, got: $implementors"
        )
    }
}
