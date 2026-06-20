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
            val rawSup = sup.substringBefore('<').trimEnd('?')
            children.getOrPut(rawSup) { mutableSetOf() }.add(type)
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

/**
 * Returns true if [fqn] (raw, no generics) has an entry in [TypedAst.typeHierarchy], meaning
 * compiled class information is available and full subtype checks will work.
 * Returns false when the jar was absent at analysis time — in that case only exact-match
 * checks via import fallback (lenient mode) can help.
 */
fun TypedAst.isTypeKnown(fqn: String): Boolean {
    val raw = fqn.substringBefore('<')
    val equivalents = typeEquivalents(raw)

    if (equivalents.any { it in typeHierarchy }) return true

    // Some types (e.g. marker interfaces, kotlin.Any) may only appear as supertypes.
    return typeHierarchy.values.any { supers -> supers.any { it in equivalents } }
}

/**
 * Lenient-mode variant of [implementorsOf].  Behaviour is identical to [implementorsOf] for
 * types that are present in the hierarchy.  When [fqName] is **not** known (jar missing), the
 * function also searches [TypedAst.files] for class declarations whose *source-declared*
 * supertype list contains a simple name that resolves to [fqName] via the file's import
 * statements, covering the case where K1 could not fully qualify the supertype.
 *
 * When [mode] is [TypeResolutionMode.LENIENT_WARN] and the type is unknown, [onWarning]
 * is called with a diagnostic message before returning.
 */
fun TypedAst.implementorsOf(
    fqName: String,
    mode: TypeResolutionMode,
    onWarning: (String) -> Unit = {},
): List<DeclarationAst> {
    val rawFqn = fqName.substringBefore('<')
    val knownInHierarchy = isTypeKnown(rawFqn)

    if (!knownInHierarchy && mode == TypeResolutionMode.LENIENT_WARN) {
        onWarning(
            "Type '$rawFqn' not found in type hierarchy — its jar may be missing. " +
            "Subtype checks will not match; only source-declared supertypes resolved via imports are checked. " +
            "Add the jar to the classpath for full hierarchy support."
        )
    }

    val strict = implementorsOf(fqName)

    if (knownInHierarchy || mode == TypeResolutionMode.STRICT) return strict

    val strictFqns = strict.map { it.fqName }.toSet()
    val fromImports = files.flatMap { file ->
        file.declarations.filter { decl ->
            decl.isClassLike() && decl.fqName !in strictFqns &&
            decl.textualSuperTypes.any { textual ->
                val rawTextual = textual.substringBefore('<').trim()
                typeNamesEquivalent(rawTextual, rawFqn) ||
                (isSimpleName(rawTextual) && resolveSimpleName(rawTextual, file.imports)
                    ?.let { typeNamesEquivalent(it, rawFqn) } == true)
            }
        }
    }

    return strict + fromImports
}

/**
 * Returns true if [actualFqn] is the same type as, or a transitive subtype of, [expectedFqn].
 * Generics are stripped before lookup. Handles Kotlin/Java mapped type equivalence
 * (e.g. kotlin.String and java.lang.String are treated as equivalent).
 *
 * Examples:
 * ```
 * ast.isSubtypeOf("java.io.Closeable", "java.io.FileInputStream") // true - FileInputStream implements Closeable
 * ast.isSubtypeOf("java.lang.String",  "kotlin.String")           // true - equivalent names
 * ast.isSubtypeOf("java.util.List",    "java.util.Set")           // false
 * ```
 */
fun TypedAst.isSubtypeOf(expectedFqn: String, actualFqn: String): Boolean {
    val rawExpected = expectedFqn.substringBefore('<').trimEnd('?')
    val rawActual = actualFqn.substringBefore('<').trimEnd('?')
    if (typeNamesEquivalent(rawExpected, rawActual)) return true
    val subtypes = allSubtypesOf(rawExpected)
    return typeEquivalents(rawActual).any { it in subtypes }
}

/** Returns [fqn] plus its Java↔Kotlin equivalent name(s), if any. */
internal fun typeEquivalents(fqn: String): Set<String> =
    setOfNotNull(fqn, KOTLIN_TO_JAVA[fqn], JAVA_TO_KOTLIN[fqn])
