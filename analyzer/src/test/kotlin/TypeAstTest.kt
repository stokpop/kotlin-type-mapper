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
import nl.stokpop.typemapper.model.TypeAst
import nl.stokpop.typemapper.model.TypeArgumentAst
import nl.stokpop.typemapper.model.TypeVariance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the [TypeAst] structured type representation, including resolved types,
 * nullable types, generics with variance, star projections, and unresolved types.
 */
class TypeAstTest {

    // ── TypeAst.toFqString() ──────────────────────────────────────────────────

    @Test
    fun `toFqString simple type`() {
        val t = TypeAst(fqName = "kotlin.String", simpleName = "String")
        assertEquals("kotlin.String", t.toFqString())
    }

    @Test
    fun `toFqString nullable type`() {
        val t = TypeAst(fqName = "kotlin.String", simpleName = "String", isNullable = true)
        assertEquals("kotlin.String?", t.toFqString())
    }

    @Test
    fun `toFqString generic type`() {
        val t = TypeAst(
            fqName = "kotlin.collections.List", simpleName = "List",
            typeArguments = listOf(
                TypeArgumentAst(type = TypeAst(fqName = "kotlin.String", simpleName = "String"))
            )
        )
        assertEquals("kotlin.collections.List<kotlin.String>", t.toFqString())
    }

    @Test
    fun `toFqString star projection`() {
        val t = TypeAst(
            fqName = "kotlin.collections.List", simpleName = "List",
            typeArguments = listOf(TypeArgumentAst(variance = TypeVariance.STAR))
        )
        assertEquals("kotlin.collections.List<*>", t.toFqString())
    }

    @Test
    fun `toFqString out variance`() {
        val t = TypeAst(
            fqName = "kotlin.collections.List", simpleName = "List",
            typeArguments = listOf(
                TypeArgumentAst(
                    variance = TypeVariance.OUT,
                    type = TypeAst(fqName = "kotlin.Any", simpleName = "Any")
                )
            )
        )
        assertEquals("kotlin.collections.List<out kotlin.Any>", t.toFqString())
    }

    @Test
    fun `toFqString in variance`() {
        val t = TypeAst(
            fqName = "kotlin.Comparable", simpleName = "Comparable",
            typeArguments = listOf(
                TypeArgumentAst(
                    variance = TypeVariance.IN,
                    type = TypeAst(fqName = "kotlin.String", simpleName = "String")
                )
            )
        )
        assertEquals("kotlin.Comparable<in kotlin.String>", t.toFqString())
    }

    @Test
    fun `toFqString nullable generic`() {
        val t = TypeAst(
            fqName = "kotlin.collections.List", simpleName = "List",
            isNullable = true,
            typeArguments = listOf(
                TypeArgumentAst(type = TypeAst(fqName = "kotlin.String", simpleName = "String"))
            )
        )
        assertEquals("kotlin.collections.List<kotlin.String>?", t.toFqString())
    }

    @Test
    fun `toFqString map with two type arguments`() {
        val t = TypeAst(
            fqName = "kotlin.collections.Map", simpleName = "Map",
            typeArguments = listOf(
                TypeArgumentAst(type = TypeAst(fqName = "kotlin.String", simpleName = "String")),
                TypeArgumentAst(type = TypeAst(fqName = "kotlin.Int", simpleName = "Int")),
            )
        )
        assertEquals("kotlin.collections.Map<kotlin.String, kotlin.Int>", t.toFqString())
    }

    @Test
    fun `toFqString unresolved type`() {
        val t = TypeAst(fqName = "HttpClient", simpleName = "HttpClient", isUnresolved = true)
        assertEquals("HttpClient", t.toFqString())
    }

    // ── Analyzer integration: resolved types ────────────────────────────────

    @Test
    fun `resolved property has TypeAst with fqName and simpleName`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                val greeting: String = "hello"
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "greeting" }
        val type = prop.type
        assertNotNull(type)
        assertEquals("kotlin.String", type!!.fqName)
        assertEquals("String", type.simpleName)
        assertFalse(type.isNullable)
        assertFalse(type.isUnresolved)
    }

    @Test
    fun `nullable property type has isNullable true`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                val maybeNull: String? = null
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "maybeNull" }
        val type = prop.type!!
        assertEquals("kotlin.String", type.fqName)
        assertTrue(type.isNullable)
    }

    @Test
    fun `generic property type has typeArguments`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                val names: List<String> = listOf()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "names" }
        val type = prop.type!!
        assertEquals("kotlin.collections.List", type.fqName)
        assertEquals("List", type.simpleName)
        assertEquals(1, type.typeArguments.size)

        val arg = type.typeArguments[0]
        assertEquals(TypeVariance.INVARIANT, arg.variance)
        assertEquals("kotlin.String", arg.type!!.fqName)
    }

    @Test
    fun `function return type is TypeAst`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                fun greet(): String = "hello"
            """.trimIndent()
        ))

        val fn = ast.files.flatMap { it.declarations }.first { it.name == "greet" }
        val ret = fn.returnType!!
        assertEquals("kotlin.String", ret.fqName)
        assertEquals("String", ret.simpleName)
        assertFalse(ret.isNullable)
    }

    @Test
    fun `function parameter type is TypeAst`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                fun greet(name: String): String = name
            """.trimIndent()
        ))

        val fn = ast.files.flatMap { it.declarations }.first { it.name == "greet" }
        val param = fn.parameters.first()
        assertEquals("name", param.name)
        assertEquals("kotlin.String", param.type.fqName)
    }

    // ── Analyzer integration: unresolved types ──────────────────────────────

    @Test
    fun `unresolved property type has isUnresolved and best-effort name`() {
        val ast = analyzeKotlinSources(mapOf(
            "Client.kt" to """
                package com.example
                import org.apache.http.client.HttpClient
                val client: HttpClient = TODO()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "client" }
        val type = prop.type!!
        assertTrue(type.isUnresolved, "Type should be marked unresolved")
        assertEquals("HttpClient", type.simpleName, "simpleName should be extracted from source")
        // FQN should be reconstructed from imports
        assertEquals("org.apache.http.client.HttpClient", type.fqName,
            "fqName should be reconstructed from file imports")
    }

    @Test
    fun `unresolved type without import uses simple name as fqName`() {
        val ast = analyzeKotlinSources(mapOf(
            "Client.kt" to """
                package com.example
                val client: UnknownType = TODO()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "client" }
        val type = prop.type!!
        assertTrue(type.isUnresolved, "Type should be marked unresolved")
        assertEquals("UnknownType", type.simpleName)
        assertEquals("UnknownType", type.fqName, "Without import, fqName falls back to simpleName")
    }

    @Test
    fun `unresolved type with single wildcard import reconstructs FQN`() {
        val ast = analyzeKotlinSources(mapOf(
            "Client.kt" to """
                package com.example
                import org.apache.http.client.*
                val client: HttpClient = TODO()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "client" }
        val type = prop.type!!
        assertTrue(type.isUnresolved)
        assertEquals("HttpClient", type.simpleName)
        assertEquals("org.apache.http.client.HttpClient", type.fqName,
            "Single wildcard import should allow FQN reconstruction")
    }

    @Test
    fun `unresolved type with multiple wildcard imports falls back to simpleName`() {
        val ast = analyzeKotlinSources(mapOf(
            "Client.kt" to """
                package com.example
                import org.apache.http.client.*
                import io.ktor.client.*
                val client: HttpClient = TODO()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "client" }
        val type = prop.type!!
        assertTrue(type.isUnresolved)
        assertEquals("HttpClient", type.simpleName)
        assertEquals("HttpClient", type.fqName,
            "Multiple wildcard imports are ambiguous — fall back to simpleName")
    }

    @Test
    fun `call site types are TypeAst`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                fun doIt() {
                    val s = "hello"
                    s.length
                }
            """.trimIndent()
        ))

        val calls = ast.files.flatMap { it.calls }
        assertTrue(calls.isNotEmpty(), "Expected at least one call site")
        val lengthCall = calls.first { it.calleeFqName.contains("length") }
        assertNotNull(lengthCall.dispatchReceiverType)
        assertEquals("kotlin.String", lengthCall.dispatchReceiverType!!.fqName)
    }

    // ── TypeAst.toFqString() matches legacy format ─────────────────────────

    @Test
    fun `star projection in analyzed code`() {
        val ast = analyzeKotlinSources(mapOf(
            "Foo.kt" to """
                package com.example
                val items: List<*> = listOf<Any>()
            """.trimIndent()
        ))

        val prop = ast.files.flatMap { it.declarations }.first { it.name == "items" }
        val type = prop.type!!
        assertTrue(type.typeArguments.any { it.variance == TypeVariance.STAR },
            "Expected star projection in type arguments")
    }
}
