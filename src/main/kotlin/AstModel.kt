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
import kotlinx.serialization.Serializable

@Serializable
data class ParameterAst(
    val name: String,
    val type: String,
)

@Serializable
data class CallSiteAst(
    val calleeFqName: String,
    val dispatchReceiverType: String? = null,    // non-null for regular method calls
    val extensionReceiverType: String? = null,   // non-null for extension function calls
    val returnType: String,
    val argumentTypes: List<String> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class DeclarationAst(
    val kind: String,                          // "function" | "property" | "class" | ...
    val name: String,
    val fqName: String,
    val containingDeclaration: String,
    val returnType: String? = null,            // function only
    val type: String? = null,                  // property / variable only
    val parameters: List<ParameterAst> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class FileAst(
    val relativePath: String,
    val packageFqName: String,
    val declarations: List<DeclarationAst>,
    val calls: List<CallSiteAst> = emptyList(),
)

@Serializable
data class TypedAst(
    val schemaVersion: String = "1.1",
    val generatedBy: String = "kotlin-type-mapper",
    val sourceRoot: String,
    val files: List<FileAst>,
)
