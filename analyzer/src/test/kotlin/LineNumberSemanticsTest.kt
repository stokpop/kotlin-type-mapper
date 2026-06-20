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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineNumberSemanticsTest {

    @Test
    fun `declaration line points to first keyword skipping KDoc`() {
        val src = """
            package com.example

            /** KDoc for Foo - line 3 */
            class Foo {
                fun bar(): String = "x"
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val cls = ast.files.flatMap { it.declarations }.first { it.name == "Foo" }
        val fn  = ast.files.flatMap { it.declarations }.first { it.name == "bar" }

        assertEquals(4, cls.line, "class keyword is on line 4; KDoc on line 3 is skipped")
        assertEquals(5, fn.line,  "fun keyword is on line 5")
    }

    @Test
    fun `endLine is populated and not less than line`() {
        val src = """
            package com.example

            class Foo {
                fun multiLine(
                    x: Int,
                    y: Int,
                ): Int {
                    return x + y
                }
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val fn = ast.files.flatMap { it.declarations }.first { it.name == "multiLine" }

        assertTrue(fn.endLine >= fn.line,
            "endLine (${fn.endLine}) must be >= line (${fn.line})")
        assertTrue(fn.endLine > fn.line,
            "multiLine function must span more than one line, got line=${fn.line} endLine=${fn.endLine}")
    }

    @Test
    fun `enum entry line skips leading KDoc`() {
        val src = """
            package com.example

            enum class Color {
                /** KDoc on line 4 */
                RED,
                GREEN,
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Color.kt" to src))
        val red = ast.files.flatMap { it.declarations }.first { it.name == "RED" }
        val green = ast.files.flatMap { it.declarations }.first { it.name == "GREEN" }

        assertEquals(5, red.line,   "RED is on line 5; KDoc on line 4 must be skipped")
        assertEquals(6, green.line, "GREEN is on line 6")
    }

    @Test
    fun `call site line is at callee name not receiver when on separate lines`() {
        val src = """
            package com.example

            fun test() {
                val s = "hello"
                s
                    .trim()
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val trimCall = ast.files.flatMap { it.calls }
            .firstOrNull { it.calleeFqName.endsWith("trim") }

        assertTrue(trimCall != null, "Expected a call to trim()")
        assertEquals(6, trimCall!!.line, "trim callee is on line 6, not receiver on line 5")
    }

    @Test
    fun `call site line is start of call expression`() {
        val src = """
            package com.example

            fun test() {
                val s = "hello"
                s.trim()
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val trimCall = ast.files.flatMap { it.calls }
            .firstOrNull { it.calleeFqName.endsWith("trim") }

        assertTrue(trimCall != null, "Expected a call to trim()")
        assertEquals(5, trimCall!!.line, "trim() call is on line 5")
    }

    @Test
    fun `call site endLine is populated and not less than line`() {
        val src = """
            package com.example

            fun test() {
                "hello".substring(
                    1,
                    3,
                )
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val call = ast.files.flatMap { it.calls }
            .firstOrNull { it.calleeFqName.endsWith("substring") }

        assertTrue(call != null, "Expected a call to substring()")
        assertTrue(call!!.endLine >= call.line,
            "endLine (${call.endLine}) must be >= line (${call.line})")
        assertTrue(call.endLine > call.line,
            "Multi-line call must span more than one line")
    }

    @Test
    fun `class declaration endLine includes closing brace`() {
        val src = """
            package com.example

            class Foo {
                val x = 1
            }
        """.trimIndent()

        val ast = analyzeKotlinSources(mapOf("Foo.kt" to src))
        val cls = ast.files.flatMap { it.declarations }
            .first { it.kind == DeclarationKind.CLASS && it.name == "Foo" }

        assertEquals(3, cls.line,    "class starts on line 3")
        assertEquals(5, cls.endLine, "class closing brace is on line 5")
    }
}
