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

/** Returns all call sites matching [sig] (static-type exact match, see [matchesSig]). */
fun TypedAst.callsMatching(sig: String): List<CallSiteAst> = calls().filter { it.matchesSig(sig) }

/** Returns all call sites whose callee FQN equals [fqName]. */
fun TypedAst.callsTo(fqName: String): List<CallSiteAst> =
    calls().filter { it.calleeFqName == fqName }

/** Returns declarations that carry an annotation with the given [fqName]. */
fun TypedAst.declarationsAnnotatedWith(fqName: String): List<DeclarationAst> =
    declarations().filter { d -> d.annotations.any { it.fqName == fqName } }

/** Returns call sites matching [sig] paired with their source file's relative path. */
fun TypedAst.callsMatchingLocated(sig: String): List<Pair<String, CallSiteAst>> =
    files.flatMap { f -> f.calls.filter { it.matchesSig(sig) }.map { f.relativePath to it } }

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

        val actualReceivers = listOfNotNull(
            call.dispatchReceiverType?.substringBefore('<'),
            call.extensionReceiverType?.substringBefore('<'),
        )
        val receiverIsSubtype = actualReceivers.any { it in subtypes || it == targetReceiver }
        if (!receiverIsSubtype) return@filter false

        if (parsed.methodName != "_") {
            val calleeName = call.calleeFqName.substringAfterLast('.')
            if (calleeName != parsed.methodName) return@filter false
        }

        if (parsed.paramTypes != null) {
            if (parsed.paramTypes.size != call.argumentTypes.size) return@filter false
            parsed.paramTypes.zip(call.argumentTypes).forEach { (exp, act) ->
                if (!typeMatches(exp, act)) return@filter false
            }
        }

        true
    }
}

/** Polymorphic version of [callsMatchingLocated]. */
fun TypedAst.callsMatchingPolymorphicLocated(sig: String): List<Pair<String, CallSiteAst>> {
    val matchingCalls = callsMatchingPolymorphic(sig).toHashSet()
    return files.flatMap { f -> f.calls.filter { it in matchingCalls }.map { f.relativePath to it } }
}
