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
import nl.stokpop.typemapper.model.TypedAst
import nl.stokpop.typemapper.model.isSubtypeOf
import nl.stokpop.typemapper.model.isTypeEquivalent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsSubtypeOfTest {

    private fun astWithHierarchy(vararg pairs: Pair<String, List<String>>): TypedAst =
        TypedAst(
            sourceRoot = "",
            files = emptyList(),
            typeHierarchy = mapOf(*pairs),
        )

    @Test
    fun `isSubtypeOf same type returns true`() {
        val ast = astWithHierarchy()
        assertTrue(ast.isSubtypeOf("java.util.List", "java.util.List"))
    }

    @Test
    fun `isSubtypeOf kotlin-java equivalent returns true`() {
        val ast = astWithHierarchy()
        assertTrue(ast.isSubtypeOf("java.lang.String", "kotlin.String"))
        assertTrue(ast.isSubtypeOf("kotlin.String", "java.lang.String"))
        assertTrue(ast.isSubtypeOf("java.util.List", "kotlin.collections.List"))
    }

    @Test
    fun `isSubtypeOf direct parent returns true`() {
        val ast = astWithHierarchy(
            "com.example.Dog" to listOf("com.example.Animal"),
        )
        assertTrue(ast.isSubtypeOf("com.example.Animal", "com.example.Dog"))
    }

    @Test
    fun `isSubtypeOf transitive parent returns true`() {
        val ast = astWithHierarchy(
            "com.example.Poodle" to listOf("com.example.Dog"),
            "com.example.Dog"    to listOf("com.example.Animal"),
        )
        assertTrue(ast.isSubtypeOf("com.example.Animal", "com.example.Poodle"))
    }

    @Test
    fun `isSubtypeOf unrelated type returns false`() {
        val ast = astWithHierarchy(
            "com.example.Dog" to listOf("com.example.Animal"),
        )
        assertFalse(ast.isSubtypeOf("com.example.Dog", "com.example.Animal"))
        assertFalse(ast.isSubtypeOf("com.example.Cat", "com.example.Dog"))
    }

    @Test
    fun `isSubtypeOf strips generics before lookup`() {
        val ast = astWithHierarchy(
            "com.example.MyList" to listOf("java.util.List"),
        )
        assertTrue(ast.isSubtypeOf("java.util.List<kotlin.String>", "com.example.MyList<kotlin.Int>"))
    }

    @Test
    fun `isSubtypeOf hierarchy value with generics still matches raw target`() {
        // typeHierarchy stores supertype with type param (source-derived); must still match raw lookup
        val ast = astWithHierarchy(
            "com.example.MyList" to listOf("java.util.List<kotlin.String>"),
        )
        assertTrue(ast.isSubtypeOf("java.util.List", "com.example.MyList"))
    }

    @Test
    fun `isSubtypeOf strips nullable marker from inputs`() {
        val ast = astWithHierarchy(
            "com.example.Dog" to listOf("com.example.Animal"),
        )
        assertTrue(ast.isSubtypeOf("com.example.Animal", "com.example.Dog?"))
        assertTrue(ast.isSubtypeOf("com.example.Animal?", "com.example.Dog"))
    }

    @Test
    fun `isTypeEquivalent kotlin-java pairs`() {
        assertTrue(isTypeEquivalent("java.lang.String", "kotlin.String"))
        assertTrue(isTypeEquivalent("kotlin.String", "java.lang.String"))
        assertTrue(isTypeEquivalent("java.util.List", "kotlin.collections.List"))
        assertFalse(isTypeEquivalent("java.util.List", "java.util.Set"))
    }

    @Test
    fun `isTypeEquivalent same name is true`() {
        assertTrue(isTypeEquivalent("com.example.Foo", "com.example.Foo"))
    }
}
