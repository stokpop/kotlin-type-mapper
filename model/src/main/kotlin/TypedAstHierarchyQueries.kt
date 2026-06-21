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
            val rawSup = sup.rawTypeName()
            children.getOrPut(rawSup) { mutableSetOf() }.add(type)
        }
    }
    val seeds = typeEquivalents(targetFqn)
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(seeds.flatMap { children[it] ?: emptySet() })
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (result.add(t)) {
            queue.addAll(children[t] ?: emptySet())
        }
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

    if (equivalents.any { it in typeHierarchy }) {
        return true
    }

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

    if (knownInHierarchy || mode == TypeResolutionMode.STRICT) {
        return strict
    }

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
 *
 * Generics and nullability are stripped before lookup -- this function checks structural
 * inheritance only, not generic instantiation. `List<String>` and `List<Integer>` are treated
 * as the same type. `String?` is treated the same as `String`.
 *
 * Handles Kotlin/Java mapped type equivalence so that e.g. `kotlin.String` and `java.lang.String`
 * are treated as equivalent. The equivalence check is pairwise: `java.util.List` == `kotlin.collections.MutableList`
 * because MutableList maps directly to java.util.List. However, `kotlin.collections.List` is NOT
 * treated as equivalent to `kotlin.collections.MutableList` even though both map to java.util.List --
 * the check is a direct pair lookup, not transitive through a shared Java name.
 *
 * Transitive lookups use [TypedAst.typeHierarchy]. If the hierarchy is empty or the type is not
 * present, only direct name/equivalence matching is performed and unrelated unknown types return false.
 *
 * `java.lang.Object` / `kotlin.Any` are short-circuited: every type is a subtype, no BFS needed.
 *
 * Examples:
 * ```
 * ast.isSubtypeOf("java.io.Closeable", "java.io.FileInputStream") // true - FileInputStream implements Closeable
 * ast.isSubtypeOf("java.lang.String",  "kotlin.String")           // true - equivalent names
 * ast.isSubtypeOf("java.util.List",    "kotlin.collections.MutableList") // true - direct java.util.List mapping
 * ast.isSubtypeOf("java.util.List<kotlin.String>", "java.util.List<kotlin.Int>") // true - generics erased
 * ast.isSubtypeOf("com.example.Animal", "com.example.Dog?")       // true - nullable marker stripped
 * ast.isSubtypeOf("java.lang.Object",  "com.example.Anything")    // true - short-circuit
 * ast.isSubtypeOf("java.util.List",    "java.util.Set")           // false - unrelated
 * ast.isSubtypeOf("kotlin.collections.List", "kotlin.collections.MutableList") // false - pairwise only
 * ast.isSubtypeOf("com.example.Foo",   "com.example.Bar")         // false - unknown with no hierarchy
 * ```
 */
fun TypedAst.isSubtypeOf(expectedFqn: String, actualFqn: String): Boolean {
    val rawExpected = expectedFqn.rawTypeName()
    // typeNamesEquivalent covers kotlin.Any via KOTLIN_TO_JAVA mapping, so one check suffices.
    if (typeNamesEquivalent(rawExpected, "java.lang.Object")) {
        return true
    }
    val rawActual = actualFqn.rawTypeName()
    if (typeNamesEquivalent(rawExpected, rawActual)) {
        return true
    }
    val subtypes = allSubtypesOf(rawExpected)
    return typeEquivalents(rawActual).any { it in subtypes }
}

/**
 * Returns true if [actualFqn] is the same type as, or a transitive subtype of, [expectedFqn],
 * by walking the **supertypes** of [actualFqn] upward through [TypedAst.typeHierarchy].
 *
 * Same contract as [isSubtypeOf] (generics stripped, nullability stripped, Java/Kotlin equivalence
 * handled). Prefer this variant for per-node rule checking where [expectedFqn] is a fixed framework
 * type and [actualFqn] varies per AST node: ancestry depth is typically 3-10 hops regardless of
 * how many subtypes [expectedFqn] has, so this is O(ancestors) vs O(all subtypes) for [isSubtypeOf].
 *
 * `java.lang.Object` (and its Kotlin equivalent `kotlin.Any`) is short-circuited: every type is a
 * subtype, no traversal needed.
 */
fun TypedAst.isSubtypeOfUpward(expectedFqn: String, actualFqn: String): Boolean {
    val rawExpected = expectedFqn.rawTypeName()
    // typeNamesEquivalent covers kotlin.Any via KOTLIN_TO_JAVA mapping, so one check suffices.
    if (typeNamesEquivalent(rawExpected, "java.lang.Object")) {
        return true
    }
    val rawActual = actualFqn.rawTypeName()
    if (typeNamesEquivalent(rawExpected, rawActual)) {
        return true
    }
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque(typeEquivalents(rawActual).toList())
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) {
            continue
        }
        for (sup in typeHierarchy[current] ?: emptyList()) {
            val rawSup = sup.rawTypeName()
            if (typeNamesEquivalent(rawExpected, rawSup)) {
                return true
            }
            queue.addAll(typeEquivalents(rawSup).filter { it !in visited })
        }
    }
    return false
}

/** Returns [fqn] plus its Java↔Kotlin equivalent name(s), if any. */
internal fun typeEquivalents(fqn: String): Set<String> =
    setOfNotNull(fqn, KOTLIN_TO_JAVA[fqn], JAVA_TO_KOTLIN[fqn])
