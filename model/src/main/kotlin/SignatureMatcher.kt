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
 * PMD-style Kotlin signature matching for call sites.
 *
 * Signature format:
 *   [receiverType "#"] methodName "(" [paramList] ")"
 *
 * receiverType  = fully-qualified type  |  "_" (match any receiver, including absent)
 * methodName    = identifier            |  "_" (match any name)  |  "<init>"
 * paramList     = paramType ("," paramType)*   (exact count)
 *               | "*"                          (any number of params)
 *               | (empty)                      (zero params)
 * paramType     = fully-qualified type  |  "_" (match any single param type)
 *
 * Receiver matching:
 *   The receiverType is checked against both dispatchReceiverType (regular method calls)
 *   and extensionReceiverType (extension function calls). A match on either is sufficient.
 *   Omitting the "receiverType#" prefix is equivalent to "_#".
 *
 * Generic types in signatures must be written in full, e.g.:
 *   kotlin.collections.List<kotlin.String>
 * Use "_" to skip a param type you don't want to constrain.
 *
 * Examples:
 *   "kotlin.String#trim()"
 *   "kotlin.String#replaceFirst(kotlin.String,kotlin.String)"
 *   "kotlin.String#replaceFirst(_,_)"
 *   "kotlin.String#_(*)"
 *   "_#trim()"
 *   "nl.stokpop.memory.MemoryCheckException#<init>(kotlin.String)"
 *   "java.util.stream.Stream#filter(java.util.function.Predicate<nl.stokpop.memory.domain.ClassGrowth>)"
 *
 * Erasure:
 *   If a type in the signature contains no angle brackets, it is matched against the
 *   raw (erased) type of the actual, mirroring PMD Java behaviour.
 *   e.g. "java.util.stream.Stream" matches "java.util.stream.Stream<MyClass>".
 *   To require an exact generic type, include the type argument in the signature.
 */
data class KotlinSig(
    /** Null means "any receiver" (wildcard _ or no # prefix). */
    val receiverType: String?,
    /** "_" means any method name. */
    val methodName: String,
    /** Null means "*" (any parameter list). Non-null list is matched by position. */
    val paramTypes: List<String>?,
)

/**
 * Parses a Kotlin signature string into a [KotlinSig].
 *
 * @throws IllegalArgumentException if the signature is malformed.
 */
fun parseSig(sig: String): KotlinSig {
    val hashIdx = sig.indexOf('#')
    val receiverPart: String?
    val methodPart: String
    if (hashIdx >= 0) {
        receiverPart = sig.substring(0, hashIdx)
        methodPart = sig.substring(hashIdx + 1)
    } else {
        receiverPart = null
        methodPart = sig
    }

    val parenIdx = methodPart.indexOf('(')
    require(parenIdx >= 0 && methodPart.endsWith(')')) {
        "Invalid signature '$sig': expected methodName(...)"
    }

    val methodName = methodPart.substring(0, parenIdx)
    require(methodName.isNotEmpty()) { "Invalid signature '$sig': method name is empty" }

    val paramsStr = methodPart.substring(parenIdx + 1, methodPart.length - 1)
    val paramTypes: List<String>? = when {
        paramsStr == "*"   -> null               // wildcard: any params
        paramsStr.isEmpty() -> emptyList()
        else               -> splitParams(paramsStr)
    }

    // Treat absent prefix and "_" both as "any receiver" (null).
    val receiverType = receiverPart?.let { if (it.isEmpty() || it == "_") null else it }

    return KotlinSig(receiverType = receiverType, methodName = methodName, paramTypes = paramTypes)
}

/** Splits a comma-separated parameter list, respecting angle-bracket nesting for generics. */
fun splitParams(params: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in params.indices) {
        when (params[i]) {
            '<' -> depth++
            '>' -> depth--
            ',' -> if (depth == 0) {
                result.add(params.substring(start, i).trim())
                start = i + 1
            }
        }
    }
    result.add(params.substring(start).trim())
    return result
}

/**
 * Checks whether an expected type pattern matches an actual fully-qualified type.
 *
 * - `"_"` matches any non-null type.
 * - Exact string equality always matches.
 * - **Erasure**: if [expected] contains no `<`, it is compared against the raw (erased)
 *   form of [actual] (everything before the first `<`), mirroring PMD Java behaviour.
 */
fun typeMatches(expected: String, actual: String?): Boolean {
    if (actual == null) return false
    if (expected == "_") return true
    if (expected == actual) return true
    // Erasure: expected has no generics → compare against raw actual.
    if ('<' !in expected) return expected == actual.substringBefore('<')
    return false
}

/**
 * Returns true if this call site matches the given signature pattern.
 *
 * @param sig A Kotlin signature pattern (see [KotlinSig] docs for format).
 */
fun CallSiteAst.matchesSig(sig: String): Boolean {
    val parsed = parseSig(sig)

    // Match method name: last dot-segment of calleeFqName, e.g. "kotlin.text.trim" → "trim".
    if (parsed.methodName != "_") {
        val calleeName = calleeFqName.substringAfterLast('.')
        if (calleeName != parsed.methodName) return false
    }

    // Match receiver: check dispatch (member calls), extension, and constructor class.
    // For constructors (callee ends with .<init>), the "receiver" is the class being
    // constructed — read from the callee FQN since no dispatch/extension receiver is set.
    // For static Java method calls (dispatch=null, extension=null), the class is the
    // package-qualified prefix of calleeFqName (everything before the last dot).
    if (parsed.receiverType != null) {
        val constructorClass = if (calleeFqName.endsWith(".<init>"))
            calleeFqName.removeSuffix(".<init>") else null
        val calleeClass = if (dispatchReceiverType == null && extensionReceiverType == null && constructorClass == null)
            calleeFqName.substringBeforeLast('.') else null
        val matches = typeMatches(parsed.receiverType, dispatchReceiverType)
                   || typeMatches(parsed.receiverType, extensionReceiverType)
                   || (constructorClass != null && typeMatches(parsed.receiverType, constructorClass))
                   || (calleeClass != null && typeMatches(parsed.receiverType, calleeClass))
        if (!matches) return false
    }

    // Match parameters: null paramTypes means "*" (skip check).
    if (parsed.paramTypes != null) {
        if (parsed.paramTypes.size != argumentTypes.size) return false
        for ((expected, actual) in parsed.paramTypes.zip(argumentTypes)) {
            if (!typeMatches(expected, actual)) return false
        }
    }

    return true
}

/** Convenience: filter a list of call sites to those matching the given signature. */
fun List<CallSiteAst>.matchesSig(sig: String): List<CallSiteAst> = filter { it.matchesSig(sig) }

/**
 * Like [typeMatches] but also accepts Java↔Kotlin equivalent names via [typeNamesEquivalent].
 *
 * e.g. `typeMatchesEquivalent("java.lang.String", "kotlin.String")` → true
 * e.g. `typeMatchesEquivalent("java.util.List",   "kotlin.collections.List<String>")` → true (erasure)
 */
fun typeMatchesEquivalent(expected: String, actual: String?): Boolean {
    if (actual == null) return false
    if (typeMatches(expected, actual)) return true
    val rawExpected = expected.substringBefore('<')
    val rawActual   = actual.substringBefore('<')
    return typeNamesEquivalent(rawExpected, rawActual)
}

/**
 * Like [matchesSig] but uses [typeMatchesEquivalent] for receiver and parameter matching,
 * so Java FQCNs and Kotlin FQNs are treated as equivalent.
 *
 * Example: `matchesSigEquivalent("java.util.regex.Pattern#matches(java.lang.String,java.lang.CharSequence)")`
 * matches a Kotlin call site whose receiver is `java.util.regex.Pattern` and whose argument
 * types are `kotlin.String` and `kotlin.CharSequence`.
 */
fun CallSiteAst.matchesSigEquivalent(sig: String): Boolean {
    val parsed = parseSig(sig)

    if (parsed.methodName != "_") {
        val calleeName = calleeFqName.substringAfterLast('.')
        if (calleeName != parsed.methodName) return false
    }

    if (parsed.receiverType != null) {
        val constructorClass = if (calleeFqName.endsWith(".<init>"))
            calleeFqName.removeSuffix(".<init>") else null
        val calleeClass = if (dispatchReceiverType == null && extensionReceiverType == null && constructorClass == null)
            calleeFqName.substringBeforeLast('.') else null
        val matches = typeMatchesEquivalent(parsed.receiverType, dispatchReceiverType)
                   || typeMatchesEquivalent(parsed.receiverType, extensionReceiverType)
                   || (constructorClass != null && typeMatchesEquivalent(parsed.receiverType, constructorClass))
                   || (calleeClass != null && typeMatchesEquivalent(parsed.receiverType, calleeClass))
        if (!matches) return false
    }

    if (parsed.paramTypes != null) {
        if (parsed.paramTypes.size != argumentTypes.size) return false
        for ((expected, actual) in parsed.paramTypes.zip(argumentTypes)) {
            if (!typeMatchesEquivalent(expected, actual)) return false
        }
    }

    return true
}

/** Convenience: filter a list of call sites using [matchesSigEquivalent]. */
fun List<CallSiteAst>.matchesSigEquivalent(sig: String): List<CallSiteAst> =
    filter { it.matchesSigEquivalent(sig) }
