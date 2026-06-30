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

private fun TypedAst.expandAlias(fqn: String): String = resolveTypeAlias(fqn) ?: fqn

/**
 * Returns the fully expanded (concrete) type string for the typealias identified by [fqn],
 * or null if [fqn] is not a known typealias in this AST.
 * The returned string is the analyzer's rendered type and may include generic arguments
 * (e.g. `"kotlin.collections.List<kotlin.String>"` for `typealias A = List<String>`).
 *
 * Example: given `typealias A = B` and `typealias B = String`,
 * `resolveTypeAlias("com.example.A")` returns `"kotlin.String"`.
 */
fun TypedAst.resolveTypeAlias(fqn: String): String? =
    typeAliasChainOf(fqn).lastOrNull()?.takeIf { it != fqn }

/**
 * Returns the full alias resolution chain for the typealias identified by [fqn],
 * starting with [fqn] itself and ending with the concrete (non-alias) type string.
 * The last element is the analyzer's rendered type and may include generic arguments
 * (e.g. `"kotlin.collections.List<kotlin.String>"` for `typealias A = List<String>`).
 * Returns an empty list if [fqn] is not a known typealias in this AST.
 *
 * Example: given `typealias A = B` and `typealias B = String`,
 * `typeAliasChainOf("com.example.A")` returns `["com.example.A", "com.example.B", "kotlin.String"]`.
 */
fun TypedAst.typeAliasChainOf(fqn: String): List<String> =
    declarations()
        .firstOrNull { it.kind == DeclarationKind.TYPEALIAS && it.fqName == fqn }
        ?.typeAliasChain
        ?: emptyList()

/**
 * Like [callsOnReceiver] but accepts a type alias FQN — expands it to the concrete type
 * before matching. Call sites always store the expanded type, so querying by alias name
 * directly via [callsOnReceiver] returns no results.
 */
fun TypedAst.callsOnReceiverAlias(fqn: String): List<CallSiteAst> =
    callsOnReceiver(expandAlias(fqn))

/**
 * Like [callsOnReceiverSubtype] but accepts a type alias FQN — expands it before matching.
 */
fun TypedAst.callsOnReceiverSubtypeAlias(fqn: String): List<CallSiteAst> =
    callsOnReceiverSubtype(expandAlias(fqn))

/**
 * Like [callsReturning] but accepts a type alias FQN — expands it before matching.
 */
fun TypedAst.callsReturningAlias(fqn: String): List<CallSiteAst> =
    callsReturning(expandAlias(fqn))

/**
 * Like [callsReturningSubtype] but accepts a type alias FQN — expands it before matching.
 */
fun TypedAst.callsReturningSubtypeAlias(fqn: String): List<CallSiteAst> =
    callsReturningSubtype(expandAlias(fqn))

/**
 * Like [constructorCallsOf] but accepts a type alias FQN — expands it before matching.
 */
fun TypedAst.constructorCallsOfAlias(fqn: String): List<CallSiteAst> =
    constructorCallsOf(expandAlias(fqn))

/**
 * Like [constructorCallsOfSubtype] but accepts a type alias FQN — expands it before matching.
 */
fun TypedAst.constructorCallsOfSubtypeAlias(fqn: String): List<CallSiteAst> =
    constructorCallsOfSubtype(expandAlias(fqn))
