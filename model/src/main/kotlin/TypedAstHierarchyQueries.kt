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

/**
 * Returns all class-like declarations whose type (or a transitive supertype) is [fqName].
 * Uses [TypedAst.typeHierarchy] built by reflection at analysis time.
 *
 * Example: `implementorsOf("java.io.Closeable")` finds every class that closes resources.
 */
fun TypedAst.implementorsOf(fqName: String): List<DeclarationAst> {
    val equivalents = typeEquivalents(fqName)
    val subtypes = equivalents.flatMap { allSubtypesOf(it) }.toSet()
    return classes().filter { decl ->
        decl.fqName in subtypes ||
        (typeHierarchy[decl.fqName] ?: emptyList()).any { it in equivalents }
    }
}

/** Alias for [implementorsOf] — finds class declarations whose type is a subtype of [fqName]. */
fun TypedAst.subtypesOf(fqName: String): List<DeclarationAst> = implementorsOf(fqName)

/**
 * Returns the set of all type FQNs that are (transitively) subtypes of [targetFqn]
 * according to [TypedAst.typeHierarchy].
 * Expands [targetFqn] via Java↔Kotlin equivalence so that e.g. `kotlin.Exception`
 * and `java.lang.Exception` return the same results.
 */
internal fun TypedAst.allSubtypesOf(targetFqn: String): Set<String> {
    val children = mutableMapOf<String, MutableSet<String>>()
    for ((type, supers) in typeHierarchy) {
        for (sup in supers) {
            children.getOrPut(sup) { mutableSetOf() }.add(type)
        }
    }
    val seeds = typeEquivalents(targetFqn)
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(seeds.flatMap { children[it] ?: emptySet() })
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (result.add(t)) queue.addAll(children[t] ?: emptySet())
    }
    return result
}

/** Returns [fqn] plus its Java↔Kotlin equivalent name(s), if any. */
internal fun typeEquivalents(fqn: String): Set<String> =
    setOfNotNull(fqn, KOTLIN_TO_JAVA[fqn], JAVA_TO_KOTLIN[fqn])
