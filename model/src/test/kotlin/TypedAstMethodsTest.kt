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
import nl.stokpop.typemapper.model.FileAst
import nl.stokpop.typemapper.model.TypedAst
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypedAstMethodsTest {

    private fun file(path: String = "Foo.kt") = FileAst(
        relativePath = path,
        packageFqName = "com.example",
        declarations = emptyList(),
    )

    // --- hasSourceRoot (member method, no extension import needed) ---

    @Test
    fun `hasSourceRoot returns false for empty sourceRoot`() {
        val ast = TypedAst(sourceRoot = "", files = emptyList())
        assertFalse(ast.hasSourceRoot())
    }

    @Test
    fun `hasSourceRoot returns true for non-empty sourceRoot`() {
        val ast = TypedAst(sourceRoot = "/some/path", files = emptyList())
        assertTrue(ast.hasSourceRoot())
    }

    // --- resolveAbsolutePath (member method, no extension import needed) ---

    @Test
    fun `resolveAbsolutePath returns null when sourceRoot is empty`() {
        val ast = TypedAst(sourceRoot = "", files = listOf(file()))
        assertNull(ast.resolveAbsolutePath(file()))
    }

    @Test
    fun `resolveAbsolutePath returns joined path when sourceRoot is set`() {
        val ast = TypedAst(sourceRoot = "/root", files = listOf(file("src/Foo.kt")))
        val result = ast.resolveAbsolutePath(file("src/Foo.kt"))
        assertEquals("/root${java.io.File.separator}src/Foo.kt", result)
    }

    @Test
    fun `resolveAbsolutePath trims trailing slash from sourceRoot`() {
        val ast = TypedAst(sourceRoot = "/root/", files = listOf(file("Foo.kt")))
        val result = ast.resolveAbsolutePath(file("Foo.kt"))
        assertEquals("/root${java.io.File.separator}Foo.kt", result)
    }
}
