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

import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

/** Renders a [KotlinType] as a fully-qualified string, including generic arguments. */
fun KotlinType.toFqString(): String {
    val descriptor = constructor.declarationDescriptor
        ?: return toString()                        // error / unresolved type
    val fqn = descriptor.fqNameSafe.asString()
    val nullable = if (isMarkedNullable) "?" else ""
    if (arguments.isEmpty()) return "$fqn$nullable"
    val args = arguments.joinToString(", ") {
        if (it.isStarProjection) "*" else it.type.toFqString()
    }
    return "$fqn<$args>$nullable"
}
