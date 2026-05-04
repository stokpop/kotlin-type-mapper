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
import nl.stokpop.typemapper.analyzer.rawTypeName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RawTypeNameTest {

    @Test
    fun `strips generic parameters`() {
        assertEquals("kotlin.collections.List", rawTypeName("kotlin.collections.List<String>"))
    }

    @Test
    fun `strips nullable marker`() {
        assertEquals("kotlin.collections.List", rawTypeName("kotlin.collections.List?"))
    }

    @Test
    fun `strips nullable marker and generic parameters`() {
        assertEquals("kotlin.collections.List", rawTypeName("kotlin.collections.List<String>?"))
    }

    @Test
    fun `bare type unchanged`() {
        assertEquals("kotlin.String", rawTypeName("kotlin.String"))
    }

    @Test
    fun `bare nullable type stripped`() {
        assertEquals("kotlin.String", rawTypeName("kotlin.String?"))
    }
}
