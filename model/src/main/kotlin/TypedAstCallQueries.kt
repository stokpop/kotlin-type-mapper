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

/**
 * Returns all call sites where the dispatch or extension receiver type matches [fqn]
 * after Kotlin/Java name mapping (e.g. `kotlin.String` matches `java.lang.String`).
 * Generics and the nullable marker (`?`) are stripped before comparison.
 */
fun TypedAst.callsOnReceiver(fqn: String): List<CallSiteAst> {
    val raw = fqn.rawTypeName()
    return calls().filter { call ->
        listOfNotNull(call.dispatchReceiverType, call.extensionReceiverType).any { recv ->
            typeNamesEquivalent(raw, recv.rawTypeName())
        }
    }
}

/**
 * Returns all call sites where the dispatch or extension receiver type is [fqn] or a subtype of
 * [fqn] according to [TypedAst.typeHierarchy]. Uses [isSubtypeOf] for equivalence and hierarchy.
 * The subtype set is precomputed once per call to avoid O(calls * hierarchy) overhead.
 */
fun TypedAst.callsOnReceiverSubtype(fqn: String): List<CallSiteAst> {
    val rawFqn = fqn.rawTypeName()
    val subtypes = allSubtypesOf(rawFqn)
    return calls().filter { call ->
        listOfNotNull(call.dispatchReceiverType, call.extensionReceiverType).any { recv ->
            val rawRecv = recv.rawTypeName()
            typeNamesEquivalent(rawFqn, rawRecv) || typeEquivalents(rawRecv).any { it in subtypes }
        }
    }
}

/**
 * Returns all call sites whose return type matches [fqn] after Kotlin/Java name mapping.
 * Generics and the nullable marker (`?`) are stripped before comparison.
 */
fun TypedAst.callsReturning(fqn: String): List<CallSiteAst> {
    val raw = fqn.rawTypeName()
    return calls().filter { typeNamesEquivalent(raw, it.returnType.rawTypeName()) }
}

/**
 * Returns all call sites whose return type is [fqn] or a subtype of [fqn]
 * according to [TypedAst.typeHierarchy]. Uses [isSubtypeOf] for equivalence and hierarchy.
 * The subtype set is precomputed once per call to avoid O(calls * hierarchy) overhead.
 */
fun TypedAst.callsReturningSubtype(fqn: String): List<CallSiteAst> {
    val rawFqn = fqn.rawTypeName()
    val subtypes = allSubtypesOf(rawFqn)
    return calls().filter { call ->
        val rawReturn = call.returnType.rawTypeName()
        typeNamesEquivalent(rawFqn, rawReturn) || typeEquivalents(rawReturn).any { it in subtypes }
    }
}

/**
 * Returns true if the dispatch receiver type of this call site is marked nullable (`?`).
 * Returns false when there is no dispatch receiver or it is non-nullable.
 * Compose with any call query: `ast.callsOnReceiver("p.Dog").filter { it.dispatchReceiverIsNullable() }`
 */
fun CallSiteAst.dispatchReceiverIsNullable(): Boolean =
    dispatchReceiverType?.endsWith('?') == true

/**
 * Returns true if the extension receiver type of this call site is marked nullable (`?`).
 * Returns false when there is no extension receiver or it is non-nullable.
 */
fun CallSiteAst.extensionReceiverIsNullable(): Boolean =
    extensionReceiverType?.endsWith('?') == true

/**
 * Returns true if the return type of this call site is marked nullable (`?`).
 */
fun CallSiteAst.returnTypeIsNullable(): Boolean =
    returnType.endsWith('?')

/**
 * Returns the FQN of the class being constructed when this is a constructor call site
 * (i.e. [CallSiteAst.calleeFqName] ends with `.<init>`), or `null` otherwise.
 * Shared with [matchesSig] and [matchesSigEquivalent] to avoid duplicating the `.<init>` detection logic.
 */
internal fun CallSiteAst.constructorClassFqn(): String? {
    if (!calleeFqName.endsWith(".<init>")) {
        return null
    }
    return calleeFqName.removeSuffix(".<init>")
}

/**
 * Returns all call sites that are constructor invocations of [fqn].
 * Handles Kotlin/Java name mapping (e.g. `kotlin.String` matches `java.lang.String.<init>`).
 * Generics and nullability are stripped before comparison.
 */
fun TypedAst.constructorCallsOf(fqn: String): List<CallSiteAst> {
    val raw = fqn.rawTypeName()
    return calls().filter { call ->
        val constructed = call.constructorClassFqn()?.rawTypeName() ?: return@filter false
        typeNamesEquivalent(raw, constructed)
    }
}

/**
 * Returns all call sites that are constructor invocations of [fqn] or any of its subtypes
 * according to [TypedAst.typeHierarchy].
 * The subtype set is precomputed once per call to avoid O(calls * hierarchy) overhead.
 */
fun TypedAst.constructorCallsOfSubtype(fqn: String): List<CallSiteAst> {
    val rawFqn = fqn.rawTypeName()
    val subtypes = allSubtypesOf(rawFqn)
    return calls().filter { call ->
        val constructed = call.constructorClassFqn()?.rawTypeName() ?: return@filter false
        typeNamesEquivalent(rawFqn, constructed) || typeEquivalents(constructed).any { it in subtypes }
    }
}
