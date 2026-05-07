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
import nl.stokpop.typemapper.model.implementorsOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyzeKotlinSourcesTest {

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
