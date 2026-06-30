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
import nl.stokpop.typemapper.model.CallSiteAst
import nl.stokpop.typemapper.model.FileAst
import nl.stokpop.typemapper.model.TypedAst
import nl.stokpop.typemapper.model.callsOnReceiver
import nl.stokpop.typemapper.model.callsOnReceiverSubtype
import nl.stokpop.typemapper.model.callsReturning
import nl.stokpop.typemapper.model.callsReturningSubtype
import nl.stokpop.typemapper.model.constructorCallsOf
import nl.stokpop.typemapper.model.constructorCallsOfSubtype
import nl.stokpop.typemapper.model.dispatchReceiverIsNullable
import nl.stokpop.typemapper.model.extensionReceiverIsNullable
import nl.stokpop.typemapper.model.returnTypeIsNullable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallSiteQueriesTest {

    private fun call(
        callee: String,
        dispatch: String? = null,
        extension: String? = null,
        returnType: String = "kotlin.Unit",
    ) = CallSiteAst(
        calleeFqName = callee,
        dispatchReceiverType = dispatch,
        extensionReceiverType = extension,
        returnType = returnType,
        line = 1, column = 1,
    )

    private fun astWith(vararg calls: CallSiteAst, hierarchy: Map<String, List<String>> = emptyMap()): TypedAst =
        TypedAst(
            sourceRoot = "",
            files = listOf(FileAst(
                relativePath = "Foo.kt",
                packageFqName = "com.example",
                declarations = emptyList(),
                calls = calls.toList(),
            )),
            typeHierarchy = hierarchy,
        )

    // --- callsOnReceiver ---

    @Test
    fun `callsOnReceiver matches dispatch receiver`() {
        val ast = astWith(
            call("com.example.Foo.doIt", dispatch = "com.example.Foo"),
            call("com.example.Bar.doIt", dispatch = "com.example.Bar"),
        )
        val result = ast.callsOnReceiver("com.example.Foo")
        assertEquals(1, result.size)
        assertEquals("com.example.Foo.doIt", result.single().calleeFqName)
    }

    @Test
    fun `callsOnReceiver matches extension receiver`() {
        val ast = astWith(
            call("kotlin.text.trim", extension = "kotlin.String"),
        )
        val result = ast.callsOnReceiver("kotlin.String")
        assertEquals(1, result.size)
    }

    @Test
    fun `callsOnReceiver handles kotlin-java equivalent names`() {
        val ast = astWith(
            call("java.lang.String.length", dispatch = "java.lang.String"),
        )
        val result = ast.callsOnReceiver("kotlin.String")
        assertEquals(1, result.size, "kotlin.String should match java.lang.String receiver")
    }

    @Test
    fun `callsOnReceiver returns empty when no match`() {
        val ast = astWith(
            call("com.example.Foo.doIt", dispatch = "com.example.Foo"),
        )
        assertTrue(ast.callsOnReceiver("com.example.Bar").isEmpty())
    }

    @Test
    fun `callsOnReceiver strips nullable marker from receiver type`() {
        val ast = astWith(
            call("com.example.Foo.doIt", dispatch = "com.example.Foo?"),
        )
        assertEquals(1, ast.callsOnReceiver("com.example.Foo").size,
            "Foo? receiver must match query for Foo")
    }

    @Test
    fun `callsOnReceiver strips generics from receiver type`() {
        val ast = astWith(
            call("java.util.List.add", dispatch = "java.util.List<kotlin.String>"),
        )
        assertEquals(1, ast.callsOnReceiver("java.util.List").size,
            "List<String> receiver must match query for raw List")
    }

    // --- callsOnReceiverSubtype ---

    @Test
    fun `callsOnReceiverSubtype includes direct receiver`() {
        val ast = astWith(
            call("com.example.Dog.bark", dispatch = "com.example.Dog"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.callsOnReceiverSubtype("com.example.Dog")
        assertEquals(1, result.size)
    }

    @Test
    fun `callsOnReceiverSubtype finds calls on subtypes`() {
        val ast = astWith(
            call("com.example.Dog.bark", dispatch = "com.example.Dog"),
            call("com.example.Animal.breathe", dispatch = "com.example.Animal"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.callsOnReceiverSubtype("com.example.Animal")
        assertEquals(2, result.size, "both Animal and Dog calls match Animal as supertypes")
    }

    @Test
    fun `callsOnReceiverSubtype returns empty when no subtype match`() {
        val ast = astWith(
            call("com.example.Cat.meow", dispatch = "com.example.Cat"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        assertTrue(ast.callsOnReceiverSubtype("com.example.Animal").isEmpty())
    }

    // --- callsReturning ---

    @Test
    fun `callsReturning matches exact return type`() {
        val ast = astWith(
            call("com.example.Factory.create", returnType = "com.example.Widget"),
            call("com.example.Factory.build",  returnType = "com.example.Gadget"),
        )
        val result = ast.callsReturning("com.example.Widget")
        assertEquals(1, result.size)
        assertEquals("com.example.Factory.create", result.single().calleeFqName)
    }

    @Test
    fun `callsReturning handles kotlin-java equivalent return types`() {
        val ast = astWith(
            call("com.example.Util.name", returnType = "java.lang.String"),
        )
        val result = ast.callsReturning("kotlin.String")
        assertEquals(1, result.size, "kotlin.String should match java.lang.String return type")
    }

    @Test
    fun `callsReturning strips nullable marker from return type`() {
        val ast = astWith(
            call("com.example.Foo.find", returnType = "com.example.Widget?"),
        )
        assertEquals(1, ast.callsReturning("com.example.Widget").size,
            "Widget? return must match query for Widget")
    }

    @Test
    fun `callsReturning strips generics from return type`() {
        val ast = astWith(
            call("com.example.Repo.all", returnType = "java.util.List<com.example.Widget>"),
        )
        assertEquals(1, ast.callsReturning("java.util.List").size,
            "List<Widget> return must match query for raw List")
    }

    @Test
    fun `callsReturning returns empty when no match`() {
        val ast = astWith(
            call("com.example.Foo.bar", returnType = "kotlin.Unit"),
        )
        assertTrue(ast.callsReturning("kotlin.String").isEmpty())
    }

    // --- callsReturningSubtype ---

    @Test
    fun `callsReturningSubtype finds calls returning subtypes`() {
        val ast = astWith(
            call("com.example.Factory.createDog", returnType = "com.example.Dog"),
            call("com.example.Factory.createAnimal", returnType = "com.example.Animal"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.callsReturningSubtype("com.example.Animal")
        assertEquals(2, result.size, "both Dog and Animal return types match Animal")
    }

    @Test
    fun `callsReturningSubtype returns empty when return type is supertype not subtype`() {
        val ast = astWith(
            call("com.example.Factory.createAnimal", returnType = "com.example.Animal"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.callsReturningSubtype("com.example.Dog")
        assertTrue(result.isEmpty(), "Animal is not a subtype of Dog")
    }

    // --- constructorCallsOf ---

    @Test
    fun `constructorCallsOf returns constructor call for exact type`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
            call("com.example.Dog.bark", dispatch = "com.example.Dog"),
        )
        val result = ast.constructorCallsOf("com.example.Dog")
        assertEquals(1, result.size)
        assertEquals("com.example.Dog.<init>", result.single().calleeFqName)
    }

    @Test
    fun `constructorCallsOf returns empty when no constructor call matches`() {
        val ast = astWith(
            call("com.example.Cat.<init>"),
        )
        assertTrue(ast.constructorCallsOf("com.example.Dog").isEmpty())
    }

    @Test
    fun `constructorCallsOf ignores non-constructor calls`() {
        val ast = astWith(
            call("com.example.Dog.bark", dispatch = "com.example.Dog"),
        )
        assertTrue(ast.constructorCallsOf("com.example.Dog").isEmpty())
    }

    @Test
    fun `constructorCallsOf handles kotlin-java equivalence`() {
        val ast = astWith(
            call("java.lang.String.<init>"),
        )
        val result = ast.constructorCallsOf("kotlin.String")
        assertEquals(1, result.size, "kotlin.String must match java.lang.String constructor")
    }

    // --- constructorCallsOfSubtype ---

    @Test
    fun `constructorCallsOfSubtype returns constructor call for direct match`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.constructorCallsOfSubtype("com.example.Dog")
        assertEquals(1, result.size)
    }

    @Test
    fun `constructorCallsOfSubtype returns constructor call for subtype`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        val result = ast.constructorCallsOfSubtype("com.example.Animal")
        assertEquals(1, result.size)
    }

    @Test
    fun `constructorCallsOfSubtype returns empty for supertype instantiation`() {
        val ast = astWith(
            call("com.example.Animal.<init>"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        assertTrue(ast.constructorCallsOfSubtype("com.example.Dog").isEmpty())
    }

    @Test
    fun `constructorCallsOfSubtype returns empty when no constructor call`() {
        val ast = astWith(
            call("com.example.Dog.bark", dispatch = "com.example.Dog"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        assertTrue(ast.constructorCallsOfSubtype("com.example.Animal").isEmpty())
    }

    @Test
    fun `constructorCallsOf strips nullable marker from query type`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
        )
        assertEquals(1, ast.constructorCallsOf("com.example.Dog?").size,
            "Nullable query Dog? must still match Dog.<init>")
    }

    @Test
    fun `constructorCallsOf strips generics from query type`() {
        val ast = astWith(
            call("com.example.Box.<init>"),
        )
        assertEquals(1, ast.constructorCallsOf("com.example.Box<kotlin.Int>").size,
            "Generic query Box<Int> must still match Box.<init>")
    }

    @Test
    fun `constructorCallsOfSubtype strips nullable marker from query type`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        assertEquals(1, ast.constructorCallsOfSubtype("com.example.Animal?").size,
            "Nullable query Animal? must still match Dog.<init>")
    }

    @Test
    fun `constructorCallsOfSubtype strips generics from query type`() {
        val ast = astWith(
            call("com.example.Dog.<init>"),
            hierarchy = mapOf("com.example.Dog" to listOf("com.example.Animal")),
        )
        assertEquals(1, ast.constructorCallsOfSubtype("com.example.Animal<kotlin.String>").size,
            "Generic query Animal<String> must still match Dog.<init>")
    }

    // --- nullable predicates ---

    @Test
    fun `dispatchReceiverIsNullable returns true for nullable receiver`() {
        val c = call("p.Dog.bark", dispatch = "p.Dog?")
        assertTrue(c.dispatchReceiverIsNullable())
    }

    @Test
    fun `dispatchReceiverIsNullable returns false for non-null receiver`() {
        val c = call("p.Dog.bark", dispatch = "p.Dog")
        assertFalse(c.dispatchReceiverIsNullable())
    }

    @Test
    fun `dispatchReceiverIsNullable returns false when no dispatch receiver`() {
        val c = call("p.Dog.bark")
        assertFalse(c.dispatchReceiverIsNullable())
    }

    @Test
    fun `extensionReceiverIsNullable returns true for nullable extension receiver`() {
        val c = call("p.ext", extension = "p.Dog?")
        assertTrue(c.extensionReceiverIsNullable())
    }

    @Test
    fun `extensionReceiverIsNullable returns false for non-null extension receiver`() {
        val c = call("p.ext", extension = "p.Dog")
        assertFalse(c.extensionReceiverIsNullable())
    }

    @Test
    fun `returnTypeIsNullable returns true for nullable return type`() {
        val c = call("p.Dog.find", returnType = "p.Dog?")
        assertTrue(c.returnTypeIsNullable())
    }

    @Test
    fun `returnTypeIsNullable returns false for non-null return type`() {
        val c = call("p.Dog.create", returnType = "p.Dog")
        assertFalse(c.returnTypeIsNullable())
    }

    @Test
    fun `nullable predicates compose with callsOnReceiver`() {
        val ast = astWith(
            call("p.Dog.bark", dispatch = "p.Dog?"),
            call("p.Dog.bark", dispatch = "p.Dog"),
        )
        val nullableOnly = ast.callsOnReceiver("p.Dog").filter { it.dispatchReceiverIsNullable() }
        val nonNullOnly  = ast.callsOnReceiver("p.Dog").filter { !it.dispatchReceiverIsNullable() }
        assertEquals(1, nullableOnly.size)
        assertEquals(1, nonNullOnly.size)
    }
}
