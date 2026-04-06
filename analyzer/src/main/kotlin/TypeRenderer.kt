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

import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.AbbreviatedType
import org.jetbrains.kotlin.types.KotlinType

/**
 * Renders a [KotlinType] as a fully-qualified string, including generic arguments.
 *
 * Typealias expansion: if the type is an [AbbreviatedType] (a typealias application such as
 * `kotlin.Exception` or `kotlin.io.Serializable`), the **expanded** underlying type is used
 * so that all downstream consumers see the canonical Java FQN (e.g. `java.lang.Exception`,
 * `java.io.Serializable`).  This also handles `TypeAliasDescriptor` entries that survive
 * without an `AbbreviatedType` wrapper.
 */
fun KotlinType.toFqString(): String {
    // unwrap() strips WrappedType / FlexibleType shells to reach the concrete SimpleType.
    // If the result is an AbbreviatedType (typealias application), use expandedType.
    val expanded: KotlinType = when (val u = unwrap()) {
        is AbbreviatedType -> u.expandedType
        else               -> u
    }

    val descriptor = expanded.constructor.declarationDescriptor
        ?: return toString()                        // error / unresolved type

    // Defensive: if the descriptor is still a TypeAliasDescriptor, recurse on its expansion.
    if (descriptor is TypeAliasDescriptor) {
        return descriptor.expandedType.toFqString()
    }

    val fqn = descriptor.fqNameSafe.asString()
    val nullable = if (isMarkedNullable) "?" else ""
    if (expanded.arguments.isEmpty()) return "$fqn$nullable"
    val args = expanded.arguments.joinToString(", ") {
        if (it.isStarProjection) "*" else it.type.toFqString()
    }
    return "$fqn<$args>$nullable"
}
