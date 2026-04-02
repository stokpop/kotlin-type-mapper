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

// ---- Flat accessors ----

fun TypedAst.declarations(): List<DeclarationAst> = files.flatMap { it.declarations }
fun TypedAst.calls(): List<CallSiteAst> = files.flatMap { it.calls }
fun TypedAst.functions(): List<DeclarationAst> = declarations().filter { it.kind == "function" }
fun TypedAst.classes(): List<DeclarationAst> = declarations().filter { it.kind in CLASS_KINDS }
fun TypedAst.properties(): List<DeclarationAst> = declarations().filter { it.kind == "property" }
fun TypedAst.fileByPath(relativePath: String): FileAst? =
    files.firstOrNull { it.relativePath == relativePath }

// ---- Signature-based call matching ----

/** Returns all call sites matching [sig] (static-type exact match, see [matchesSig]). */
fun TypedAst.callsMatching(sig: String): List<CallSiteAst> = calls().filter { it.matchesSig(sig) }

/** Returns all call sites whose callee FQN equals [fqName]. */
fun TypedAst.callsTo(fqName: String): List<CallSiteAst> =
    calls().filter { it.calleeFqName == fqName }

// ---- Annotation-based ----

/** Returns declarations that carry an annotation with the given [fqName]. */
fun TypedAst.declarationsAnnotatedWith(fqName: String): List<DeclarationAst> =
    declarations().filter { d -> d.annotations.any { it.fqName == fqName } }

// ---- Type-hierarchy queries (use reflection-built typeHierarchy) ----

/**
 * Returns all class-like declarations whose type (or a transitive supertype) is [fqName].
 * Uses [TypedAst.typeHierarchy] built by reflection at analysis time.
 *
 * Example: `implementorsOf("java.io.Closeable")` finds every class that closes resources.
 */
fun TypedAst.implementorsOf(fqName: String): List<DeclarationAst> {
    val subtypes = allSubtypesOf(fqName)
    return classes().filter { it.fqName in subtypes || fqName in (typeHierarchy[it.fqName] ?: emptyList()) }
}

/** Alias for [implementorsOf] — finds class declarations whose type is a subtype of [fqName]. */
fun TypedAst.subtypesOf(fqName: String): List<DeclarationAst> = implementorsOf(fqName)

/**
 * Returns all call sites where the receiver type is a subtype of the receiver specified in [sig],
 * or a direct match. Uses [TypedAst.typeHierarchy] for hierarchy traversal.
 *
 * Example: `callsMatchingPolymorphic("kotlin.Any#equals(_)")` matches every `equals` call
 * regardless of the concrete receiver type.
 */
fun TypedAst.callsMatchingPolymorphic(sig: String): List<CallSiteAst> {
    val parsed = parseSig(sig)
    val targetReceiver = parsed.receiverType
        ?: return callsMatching(sig)   // no receiver constraint — same as callsMatching

    val subtypes = allSubtypesOf(targetReceiver)

    return calls().filter { call ->
        if (call.matchesSig(sig)) return@filter true

        // Check whether the actual receiver is a known subtype of the target receiver.
        val actualReceivers = listOfNotNull(
            call.dispatchReceiverType?.substringBefore('<'),
            call.extensionReceiverType?.substringBefore('<'),
        )
        val receiverIsSubtype = actualReceivers.any { it in subtypes || it == targetReceiver }
        if (!receiverIsSubtype) return@filter false

        // Method name check.
        if (parsed.methodName != "_") {
            val calleeName = call.calleeFqName.substringAfterLast('.')
            if (calleeName != parsed.methodName) return@filter false
        }

        // Parameter count/type check.
        if (parsed.paramTypes != null) {
            if (parsed.paramTypes.size != call.argumentTypes.size) return@filter false
            parsed.paramTypes.zip(call.argumentTypes).forEach { (exp, act) ->
                if (!typeMatches(exp, act)) return@filter false
            }
        }

        true
    }
}

// ---- Internal helpers ----

/**
 * Returns the set of all type FQNs that are (transitively) subtypes of [targetFqn]
 * according to [TypedAst.typeHierarchy].
 */
private fun TypedAst.allSubtypesOf(targetFqn: String): Set<String> {
    // Invert the hierarchy: for each type, collect all types that have it as a (direct) supertype.
    val children = mutableMapOf<String, MutableSet<String>>()
    for ((type, supers) in typeHierarchy) {
        for (sup in supers) {
            children.getOrPut(sup) { mutableSetOf() }.add(type)
        }
    }
    // BFS from targetFqn downward.
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(children[targetFqn] ?: emptySet())
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (result.add(t)) queue.addAll(children[t] ?: emptySet())
    }
    return result
}
