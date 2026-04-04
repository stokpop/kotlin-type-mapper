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
package nl.stokpop.typemapper.model

import kotlinx.serialization.Serializable

/** Declaration kinds that represent class-like types (have a type hierarchy entry). */
val CLASS_KINDS = setOf("class", "data_class", "sealed_class", "enum", "interface", "annotation", "object", "companion_object")

@Serializable
data class AnnotationAst(
    val fqName: String,
    val arguments: List<String> = emptyList(),
)

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
    val annotations: List<AnnotationAst> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class FileAst(
    val relativePath: String,
    val packageFqName: String,
    val declarations: List<DeclarationAst>,
    val calls: List<CallSiteAst> = emptyList(),
    val contentHash: String = "",              // SHA-256 of source file content
)

@Serializable
data class TypedAst(
    val schemaVersion: String = "1.3",
    val generatedBy: String = "kotlin-type-mapper",
    val sourceRoot: String,
    val files: List<FileAst>,
    /** Direct supertypes per type FQN, built via reflection at analysis time.
     *  Key: raw type FQN (no generics). Value: list of direct supertype FQNs.
     *  Kotlin-mapped names are used (e.g. kotlin.Any, kotlin.collections.List). */
    val typeHierarchy: Map<String, List<String>> = emptyMap(),
)
