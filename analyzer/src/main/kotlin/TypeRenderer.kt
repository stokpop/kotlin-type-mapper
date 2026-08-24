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
package nl.stokpop.typemapper.analyzer

import nl.stokpop.typemapper.model.TypeAst
import nl.stokpop.typemapper.model.TypeArgumentAst
import nl.stokpop.typemapper.model.TypeVariance
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.AbbreviatedType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.error.ErrorUtils

/**
 * Renders a [KotlinType] as a fully-qualified string, including generic arguments.
 *
 * Typealias expansion: if the type is an [AbbreviatedType] (a typealias application such as
 * `kotlin.Exception` or `kotlin.io.Serializable`), the **expanded** underlying type is used
 * so that all downstream consumers see the canonical Java FQN (e.g. `java.lang.Exception`,
 * `java.io.Serializable`).  This also handles `TypeAliasDescriptor` entries that survive
 * without an `AbbreviatedType` wrapper.
 */
fun KotlinType.toFqString(): String = toTypeAst().toFqString()

/**
 * Converts a [KotlinType] to a structured [TypeAst] representation.
 *
 * @param imports explicit import FQNs from the source file, used for best-effort
 *   FQN reconstruction when the type is unresolved (missing classpath dependency).
 */
fun KotlinType.toTypeAst(imports: List<String> = emptyList()): TypeAst {
    val expanded: KotlinType = when (val u = unwrap()) {
        is AbbreviatedType -> u.expandedType
        else               -> u
    }

    val descriptor = expanded.constructor.declarationDescriptor

    if (descriptor == null || ErrorUtils.isError(descriptor)) {
        // Error / unresolved type — extract best-effort name from the type constructor text.
        // Kotlin error types have constructors with toString() like "[ERROR : SimpleName]"
        // or descriptors named "<Error class: unknown class>".
        val rawText = expanded.constructor.toString()
        val simpleName = extractErrorTypeName(rawText)
        if (simpleName == null) {
            // Cannot extract a meaningful name — return a placeholder
            return TypeAst(
                fqName = "",
                simpleName = "",
                isNullable = isMarkedNullable,
                isUnresolved = true,
            )
        }
        val fqName = if ('.' in simpleName) {
            simpleName // already qualified
        } else {
            // Attempt FQN reconstruction from imports:
            // 1. Try explicit import match (e.g. "my.package.HttpClient")
            // 2. If none, try single wildcard import (e.g. "my.package.*" → "my.package.HttpClient")
            imports.firstOrNull { it.endsWith(".$simpleName") }
                ?: run {
                    val wildcards = imports.filter { it.endsWith(".*") }
                    if (wildcards.size == 1) {
                        wildcards[0].removeSuffix("*") + simpleName
                    } else {
                        simpleName
                    }
                }
        }
        return TypeAst(
            fqName = fqName,
            simpleName = simpleName.substringAfterLast('.'),
            isNullable = isMarkedNullable,
            isUnresolved = true,
            typeArguments = expanded.arguments.map { it.toTypeArgumentAst(imports) },
        )
    }

    // Defensive: if the descriptor is still a TypeAliasDescriptor, recurse on its expansion.
    if (descriptor is TypeAliasDescriptor) {
        return descriptor.expandedType.toTypeAst(imports)
    }

    val fqn = descriptor.fqNameSafe.asString()
    val simpleName = descriptor.name.asString()

    return TypeAst(
        fqName = fqn,
        simpleName = simpleName,
        isNullable = isMarkedNullable,
        isUnresolved = false,
        typeArguments = expanded.arguments.map { it.toTypeArgumentAst(imports) },
    )
}

/** Converts a Kotlin type projection to a [TypeArgumentAst]. */
private fun org.jetbrains.kotlin.types.TypeProjection.toTypeArgumentAst(
    imports: List<String> = emptyList(),
): TypeArgumentAst {
    if (isStarProjection) {
        return TypeArgumentAst(variance = TypeVariance.STAR)
    }
    val variance = when (projectionKind) {
        Variance.INVARIANT     -> TypeVariance.INVARIANT
        Variance.IN_VARIANCE   -> TypeVariance.IN
        Variance.OUT_VARIANCE  -> TypeVariance.OUT
    }
    return TypeArgumentAst(variance = variance, type = type.toTypeAst(imports))
}

/**
 * Extracts the source-level type name from an error type's constructor string representation.
 * Handles formats like:
 * - `[ERROR : HttpClient]`
 * - `[Error type: Unresolved type for HttpClient`
 * - `<Error class: unknown class>`
 *
 * Returns null when no meaningful name can be extracted.
 */
private fun extractErrorTypeName(rawText: String): String? {
    // Format: "[ERROR : SimpleName]"
    if (rawText.startsWith("[ERROR : ")) {
        val name = rawText.removePrefix("[ERROR : ").removeSuffix("]")
            .substringBefore('<').trim()
        if (name.isNotEmpty() && !name.startsWith("<Error")) return name
    }
    // Format: "[Error type: Unresolved type for SimpleName"
    if (rawText.startsWith("[Error type: Unresolved type for ")) {
        val name = rawText.removePrefix("[Error type: Unresolved type for ")
            .removeSuffix("]").substringBefore('<').trim()
        if (name.isNotEmpty()) return name
    }
    // Format: "<Error class: unknown class>" or other unrecognized
    if (rawText.startsWith("<Error") || rawText.isEmpty()) return null
    // Fallback: try the raw text itself
    val cleaned = rawText.removeSurrounding("[", "]").substringBefore('<').trim()
    return cleaned.ifEmpty { null }
}
