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
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Utilities for serialising and deserialising [TypedAst] to/from JSON.
 * Uses [ignoreUnknownKeys] so that older JSON files (schema v1.1) load cleanly.
 */
object TypedAstJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(file: File): TypedAst = fromString(file.readText())

    fun save(ast: TypedAst, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJsonString(ast))
    }

    fun fromString(jsonString: String): TypedAst = json.decodeFromString(jsonString)

    fun toJsonString(ast: TypedAst): String = json.encodeToString(ast)
}
