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
import nl.stokpop.typemapper.analyzer.KotlinTypeMapper
import nl.stokpop.typemapper.model.calls
import nl.stokpop.typemapper.model.callsOnReceiver
import nl.stokpop.typemapper.model.callsMatching
import nl.stokpop.typemapper.model.implementorsOf
import nl.stokpop.typemapper.model.TypeResolutionMode
import nl.stokpop.typemapper.model.isTypeKnown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `imports are captured in FileAst`() {
        val sources = mapOf(
            "Client.kt" to """
                package com.example

                import org.apache.http.client.HttpClient
                import org.apache.http.impl.client.DefaultHttpClient

                class MyClient : HttpClient
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val file = ast.files.first()

        assertTrue(
            "org.apache.http.client.HttpClient" in file.imports,
            "Expected HttpClient in imports, got: ${file.imports}"
        )
        assertTrue(
            "org.apache.http.impl.client.DefaultHttpClient" in file.imports,
            "Expected DefaultHttpClient in imports, got: ${file.imports}"
        )
    }

    @Test
    fun `isTypeKnown returns false for missing jar type`() {
        val sources = mapOf(
            "Client.kt" to """
                package com.example

                import org.apache.http.client.HttpClient

                class MyClient : HttpClient
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        assertFalse(ast.isTypeKnown("org.apache.http.client.HttpClient"),
            "HttpClient jar not on classpath — should not be known")
    }

    @Test
    fun `textualSuperTypes captures PSI text when jar missing`() {
        val sources = mapOf(
            "Client.kt" to """
                package com.example

                import org.apache.http.client.HttpClient

                class MyClient : HttpClient
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val myClient = ast.files.flatMap { it.declarations }.first { it.name == "MyClient" }

        assertTrue(
            myClient.textualSuperTypes.any { it == "HttpClient" },
            "Expected textualSuperTypes to contain 'HttpClient', got: ${myClient.textualSuperTypes}"
        )
    }

    @Test
    fun `lenient implementorsOf resolves supertype via imports when jar missing`() {
        val sources = mapOf(
            "Client.kt" to """
                package com.example

                import org.apache.http.client.HttpClient

                class MyClient : HttpClient
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)

        val strict = ast.implementorsOf("org.apache.http.client.HttpClient")
        val warnings = mutableListOf<String>()
        val lenient = ast.implementorsOf(
            "org.apache.http.client.HttpClient",
            TypeResolutionMode.LENIENT_WARN
        ) { warnings.add(it) }

        assertTrue(lenient.any { it.fqName == "com.example.MyClient" },
            "Lenient mode should find MyClient via import resolution. Strict found: ${strict.map { it.fqName }}")
        assertTrue(warnings.isNotEmpty(), "LENIENT_WARN should emit a warning when jar is missing")
    }

    @Test
    fun `lenient quiet implementorsOf emits no warning`() {
        val sources = mapOf(
            "Client.kt" to """
                package com.example

                import org.apache.http.client.HttpClient

                class MyClient : HttpClient
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val warnings = mutableListOf<String>()
        ast.implementorsOf("org.apache.http.client.HttpClient", TypeResolutionMode.LENIENT_QUIET) { warnings.add(it) }

        assertTrue(warnings.isEmpty(), "LENIENT_QUIET should not emit any warning")
    }

    @Test
    fun `fromPaths analyzes a source file and finds declarations`(@TempDir tempDir: File) {
        val src = File(tempDir, "Foo.kt").also { it.writeText("package com.example\nclass Foo") }
        val ast = KotlinTypeMapper.fromPaths(listOf(src.toPath()))
        val decls = ast.files.flatMap { it.declarations }
        assertTrue(
            decls.any { it.fqName == "com.example.Foo" },
            "Expected com.example.Foo in declarations, got: ${decls.map { it.fqName }}"
        )
    }

    @Test
    fun `fromPaths resolveAbsolutePath matches canonical path`(@TempDir tempDir: File) {
        val src = File(tempDir, "Bar.kt").also { it.writeText("package com.example\nclass Bar") }
        val ast = KotlinTypeMapper.fromPaths(listOf(src.toPath()))
        val file = ast.files.first()
        val resolved = ast.resolveAbsolutePath(file)
        assertNotNull(resolved, "resolveAbsolutePath must return non-null for file-list analysis")
        assertEquals(src.canonicalPath, File(resolved!!).canonicalPath)
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

    @Test
    fun `call inside with block has typed dispatch receiver and is found by queries`() {
        val sources = mapOf(
            "Foo.kt" to """
                package com.example

                class Dog { fun bark(): Unit = Unit }

                fun test(dog: Dog) {
                    with(dog) { bark() }
                }
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val barkCall = ast.calls().find { it.calleeFqName.endsWith(".bark") }

        assertNotNull(barkCall, "Expected to find bark() call inside with block")
        assertEquals(
            "com.example.Dog",
            barkCall!!.dispatchReceiverType,
            "dispatchReceiverType must resolve for implicit this inside with block"
        )
        assertEquals(1, ast.callsOnReceiver("com.example.Dog").size,
            "callsOnReceiver must find bark() via with-block receiver")
        assertEquals(1, ast.callsMatching("com.example.Dog#bark()").size,
            "callsMatching must find bark() via with-block receiver")
    }

    @Test
    fun `call on implicit lambda it parameter has typed dispatch receiver`() {
        val sources = mapOf(
            "Foo.kt" to """
                package com.example

                class Dog { fun bark(): Unit = Unit }

                fun test(dogs: List<Dog>) {
                    dogs.forEach { it.bark() }
                }
            """.trimIndent()
        )

        val ast = analyzeKotlinSources(sources)
        val barkCall = ast.calls().find { it.calleeFqName.endsWith(".bark") }

        assertNotNull(barkCall, "Expected to find bark() call inside lambda")
        assertEquals(
            "com.example.Dog",
            barkCall!!.dispatchReceiverType,
            "dispatchReceiverType for implicit it must resolve to the lambda parameter type"
        )
    }
}
