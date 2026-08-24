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
import nl.stokpop.typemapper.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeArgumentQueriesTest {

    private fun typeOf(fqn: String, vararg args: TypeAst): TypeAst {
        val nullable = fqn.endsWith('?')
        val raw = fqn.trimEnd('?')
        return TypeAst(
            fqName = raw,
            simpleName = raw.substringAfterLast('.'),
            isNullable = nullable,
            typeArguments = args.map { TypeArgumentAst(type = it) },
        )
    }

    private val listOfMyType = TypeAst(
        fqName = "kotlin.collections.List",
        simpleName = "List",
        typeArguments = listOf(
            TypeArgumentAst(type = TypeAst(fqName = "com.example.MyType", simpleName = "MyType"))
        ),
    )

    private val mapStringMyType = TypeAst(
        fqName = "kotlin.collections.Map",
        simpleName = "Map",
        typeArguments = listOf(
            TypeArgumentAst(type = TypeAst(fqName = "kotlin.String", simpleName = "String")),
            TypeArgumentAst(type = TypeAst(fqName = "com.example.MyType", simpleName = "MyType")),
        ),
    )

    private val nestedListOfMyType = TypeAst(
        fqName = "kotlin.collections.List",
        simpleName = "List",
        typeArguments = listOf(
            TypeArgumentAst(type = TypeAst(
                fqName = "kotlin.collections.List",
                simpleName = "List",
                typeArguments = listOf(
                    TypeArgumentAst(type = TypeAst(fqName = "com.example.MyType", simpleName = "MyType"))
                ),
            ))
        ),
    )

    private val plainString = TypeAst(fqName = "kotlin.String", simpleName = "String")

    private fun astWith(
        declarations: List<DeclarationAst> = emptyList(),
        calls: List<CallSiteAst> = emptyList(),
    ) = TypedAst(
        sourceRoot = "",
        files = listOf(FileAst(
            relativePath = "Foo.kt",
            packageFqName = "com.example",
            declarations = declarations,
            calls = calls,
        )),
    )

    private fun decl(name: String, type: TypeAst) = DeclarationAst(
        kind = DeclarationKind.PROPERTY,
        name = name,
        fqName = "com.example.$name",
        containingDeclaration = "com.example",
        type = type,
        line = 1, column = 1,
    )

    private fun call(callee: String, returnType: TypeAst) = CallSiteAst(
        calleeFqName = callee,
        returnType = returnType,
        line = 1, column = 1,
    )

    // ── declarationsWithTypeArgument ──────────────────────────────────────────

    @Test
    fun `finds declaration with direct type argument`() {
        val ast = astWith(declarations = listOf(
            decl("items", listOfMyType),
            decl("name", plainString),
        ))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("items", results[0].name)
    }

    @Test
    fun `finds declaration with type argument in map value position`() {
        val ast = astWith(declarations = listOf(
            decl("lookup", mapStringMyType),
        ))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("lookup", results[0].name)
    }

    @Test
    fun `finds declaration with nested type argument`() {
        val ast = astWith(declarations = listOf(
            decl("nested", nestedListOfMyType),
        ))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("nested", results[0].name)
    }

    @Test
    fun `does not match declaration without matching type argument`() {
        val ast = astWith(declarations = listOf(
            decl("name", plainString),
        ))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `finds declaration where returnType has type argument`() {
        val fn = DeclarationAst(
            kind = DeclarationKind.FUNCTION,
            name = "getItems",
            fqName = "com.example.getItems",
            containingDeclaration = "com.example",
            returnType = listOfMyType,
            line = 1, column = 1,
        )
        val ast = astWith(declarations = listOf(fn))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("getItems", results[0].name)
    }

    @Test
    fun `finds declaration where parameter type has type argument`() {
        val fn = DeclarationAst(
            kind = DeclarationKind.FUNCTION,
            name = "process",
            fqName = "com.example.process",
            containingDeclaration = "com.example",
            returnType = plainString,
            parameters = listOf(ParameterAst(name = "items", type = listOfMyType)),
            line = 1, column = 1,
        )
        val ast = astWith(declarations = listOf(fn))

        val results = ast.declarationsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("process", results[0].name)
    }

    // ── callsWithTypeArgument ────────────────────────────────────────────────

    @Test
    fun `finds call where return type has type argument`() {
        val ast = astWith(calls = listOf(
            call("kotlin.collections.listOf", listOfMyType),
            call("kotlin.String.length", plainString),
        ))

        val results = ast.callsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
        assertEquals("kotlin.collections.listOf", results[0].calleeFqName)
    }

    @Test
    fun `finds call where dispatch receiver has type argument`() {
        val c = CallSiteAst(
            calleeFqName = "kotlin.collections.List.size",
            dispatchReceiverType = listOfMyType,
            returnType = TypeAst(fqName = "kotlin.Int", simpleName = "Int"),
            line = 1, column = 1,
        )
        val ast = astWith(calls = listOf(c))

        val results = ast.callsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
    }

    @Test
    fun `finds call where argument type has type argument`() {
        val c = CallSiteAst(
            calleeFqName = "com.example.process",
            returnType = plainString,
            argumentTypes = listOf(listOfMyType),
            line = 1, column = 1,
        )
        val ast = astWith(calls = listOf(c))

        val results = ast.callsWithTypeArgument("com.example.MyType")
        assertEquals(1, results.size)
    }

    @Test
    fun `does not match call without matching type argument`() {
        val ast = astWith(calls = listOf(
            call("kotlin.String.length", plainString),
        ))

        val results = ast.callsWithTypeArgument("com.example.MyType")
        assertTrue(results.isEmpty())
    }
}
