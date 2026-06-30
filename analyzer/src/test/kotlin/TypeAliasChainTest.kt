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
import nl.stokpop.typemapper.model.DeclarationKind
import nl.stokpop.typemapper.model.callsOnReceiver
import nl.stokpop.typemapper.model.callsOnReceiverAlias
import nl.stokpop.typemapper.model.callsOnReceiverSubtype
import nl.stokpop.typemapper.model.callsReturningAlias
import nl.stokpop.typemapper.model.constructorCallsOfAlias
import nl.stokpop.typemapper.model.expandAlias
import nl.stokpop.typemapper.model.resolveTypeAlias
import nl.stokpop.typemapper.model.typeAliasChainOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeAliasChainTest {

    @Test
    fun `simple typealias chain has two elements`() {
        val src = """
            package com.example
            typealias MyStr = String
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Aliases.kt" to src))
        val decl = ast.files.flatMap { it.declarations }
            .first { it.kind == DeclarationKind.TYPEALIAS && it.name == "MyStr" }

        assertEquals(
            listOf("com.example.MyStr", "kotlin.String"),
            decl.typeAliasChain,
            "simple alias chain must be [alias, concrete]"
        )
    }

    @Test
    fun `chained typealiases produce full chain`() {
        val src = """
            package com.example
            typealias B = String
            typealias A = B
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Aliases.kt" to src))
        val declA = ast.files.flatMap { it.declarations }
            .first { it.kind == DeclarationKind.TYPEALIAS && it.name == "A" }

        assertEquals(
            listOf("com.example.A", "com.example.B", "kotlin.String"),
            declA.typeAliasChain,
            "chained alias chain must include intermediate alias"
        )
    }

    @Test
    fun `non-alias declaration has empty typeAliasChain`() {
        val src = """
            package com.example
            class Foo
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val decl = ast.files.flatMap { it.declarations }
            .first { it.name == "Foo" }

        assertTrue(decl.typeAliasChain.isEmpty(), "class declaration must have empty chain")
    }

    @Test
    fun `resolveTypeAlias returns final concrete type`() {
        val src = """
            package com.example
            typealias B = String
            typealias A = B
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Aliases.kt" to src))
        assertEquals("kotlin.String", ast.resolveTypeAlias("com.example.A"))
        assertEquals("kotlin.String", ast.resolveTypeAlias("com.example.B"))
    }

    @Test
    fun `resolveTypeAlias returns null for unknown fqn`() {
        val src = """
            package com.example
            class Foo
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        assertNull(ast.resolveTypeAlias("com.example.NoSuchAlias"))
    }

    @Test
    fun `typeAliasChainOf returns chain for known alias`() {
        val src = """
            package com.example
            typealias MyStr = String
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Aliases.kt" to src))
        assertEquals(
            listOf("com.example.MyStr", "kotlin.String"),
            ast.typeAliasChainOf("com.example.MyStr")
        )
    }

    @Test
    fun `typeAliasChainOf returns empty for unknown fqn`() {
        val src = """
            package com.example
            class Foo
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        assertTrue(ast.typeAliasChainOf("com.example.NoAlias").isEmpty())
    }

    // --- alias-aware call queries ---

    private val aliasSources = mapOf("Aliases.kt" to """
        package com.example

        open class Animal
        class Dog : Animal() { fun bark(): Unit = Unit }
        typealias MyDog = Dog

        fun callOnAlias(d: MyDog) { d.bark() }
        fun returnAlias(): MyDog = Dog()
        fun constructAlias(): MyDog = MyDog()
    """.trimIndent())

    @Test
    fun `callsOnReceiver misses calls on aliased receiver type`() {
        val ast = analyzeKotlinSources(aliasSources)
        assertTrue(ast.callsOnReceiver("com.example.MyDog").isEmpty(),
            "callsOnReceiver must not match alias name — K1 expands aliases in call sites")
    }

    @Test
    fun `callsOnReceiverAlias finds calls on aliased receiver type`() {
        val ast = analyzeKotlinSources(aliasSources)
        val result = ast.callsOnReceiverAlias("com.example.MyDog")
        assertEquals(1, result.size)
        assertEquals("com.example.Dog.bark", result.single().calleeFqName)
    }

    @Test
    fun `expandAlias composes with callsOnReceiverSubtype`() {
        // expandAlias("MyDog") == "Dog"; then callsOnReceiverSubtype("Dog") finds Dog calls
        val ast = analyzeKotlinSources(aliasSources)
        val result = ast.callsOnReceiverSubtype(ast.expandAlias("com.example.MyDog"))
        assertEquals(1, result.size)
        assertEquals("com.example.Dog.bark", result.single().calleeFqName)
    }

    @Test
    fun `callsReturningAlias finds calls whose return type matches expanded alias`() {
        val ast = analyzeKotlinSources(aliasSources)
        val result = ast.callsReturningAlias("com.example.MyDog")
        assertTrue(result.any { it.calleeFqName.endsWith("Dog.<init>") },
            "Constructor call returning Dog must be found via MyDog alias")
    }

    @Test
    fun `constructorCallsOfAlias finds constructor calls for aliased type`() {
        val ast = analyzeKotlinSources(aliasSources)
        val result = ast.constructorCallsOfAlias("com.example.MyDog")
        assertEquals(1, result.size)
        assertTrue(result.single().calleeFqName.endsWith("Dog.<init>"))
    }

    @Test
    fun `typealias with generic argument has rendered type string as last chain element`() {
        val src = """
            package com.example
            typealias StringList = List<String>
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Aliases.kt" to src))
        val chain = ast.typeAliasChainOf("com.example.StringList")
        assertEquals("com.example.StringList", chain.first())
        // last element includes generic argument — not a bare FQN
        assertTrue(chain.last().startsWith("kotlin.collections.List<"),
            "Last chain element must include generic: got ${chain.last()}")
    }
}
