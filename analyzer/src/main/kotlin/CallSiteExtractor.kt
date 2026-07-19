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

import nl.stokpop.typemapper.model.CallSiteAst
import nl.stokpop.typemapper.model.kotlinToJavaName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Returns true if this is a Kotlin stdlib extension function whose extension receiver
 * maps to a Java class (e.g. `kotlin.text.indexOf` extends `kotlin.String`).
 * For such calls we synthesise an additional call site that looks like a plain Java
 * dispatch-receiver call, using only the required (non-default) parameters.
 * This allows `matchesSig('java.lang.String#indexOf(java.lang.String)')` to work even
 * though the Kotlin compiler resolves the call as an extension with extra default params.
 */
private fun shouldSynthesizeJavaCallSite(
    extensionReceiverType: String?,
    calleeFqName: String
): Boolean {
    if (extensionReceiverType == null) return false
    // Only for stdlib extension functions whose receiver maps to a Java type.
    val javaType = kotlinToJavaName(extensionReceiverType)
    if (javaType == extensionReceiverType) return false   // no mapping → not a stdlib Java type
    val pkg = calleeFqName.substringBeforeLast('.')
    return pkg.startsWith("kotlin.")
}

@OptIn(KaExperimentalApi::class)
internal fun KaSession.extractCallSites(ktFile: KtFile): List<CallSiteAst> {
    val calls = mutableListOf<CallSiteAst>()
    val doc = ktFile.viewProvider.document

    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }
    fun endLineOf(endOffset: Int) = lineOf((endOffset - 1).coerceAtLeast(0))
    fun endColOf(endOffset: Int) = colOf((endOffset - 1).coerceAtLeast(0))

    ktFile.accept(object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
            super.visitCallExpression(expression)
            val call = expression.resolveToCall()?.successfulCallOrNull<KaFunctionCall<*>>() ?: return
            // Constructor symbols have no callableId; derive "<class>.<init>" from the containing class
            // so constructor call sites are captured (matches the K1 ".<init>" convention).
            val symbol = call.symbol
            val calleeFqName = when (symbol) {
                is KaConstructorSymbol ->
                    symbol.containingClassId?.asSingleFqName()?.asString()?.let { "$it.<init>" } ?: return
                else -> symbol.callableId?.asSingleFqName()?.asString() ?: return
            }
            val offset = expression.textRange.startOffset
            val extReceiverType = call.partiallyAppliedSymbol.extensionReceiver?.type?.let { renderType(it) }
            val allArgTypes = call.symbol.valueParameters.map { renderType(it.returnType) }
            val endOffset = expression.textRange.endOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = calleeFqName,
                    dispatchReceiverType = call.partiallyAppliedSymbol.dispatchReceiver?.type?.let { renderType(it) },
                    extensionReceiverType = extReceiverType,
                    returnType = renderType(call.symbol.returnType),
                    argumentTypes = allArgTypes,
                    line = lineOf(offset), column = colOf(offset),
                    endLine = endLineOf(endOffset), endColumn = endColOf(endOffset),
                )
            )
            // For Kotlin stdlib extension functions on Java-mapped types (e.g. kotlin.text.indexOf
            // extending kotlin.String), also emit a synthetic call site that looks like a plain
            // Java dispatch-receiver call using only the required (non-default) parameters.
            // This lets matchesSig('java.lang.String#indexOf(java.lang.String)') work even though
            // the Kotlin compiler resolves the call with extra default params.
            if (shouldSynthesizeJavaCallSite(extReceiverType, calleeFqName)) {
                val requiredArgTypes = call.symbol.valueParameters
                    .filter { !it.hasDefaultValue }
                    .map { renderType(it.returnType) }
                calls.add(
                    CallSiteAst(
                        calleeFqName = calleeFqName,
                        dispatchReceiverType = extReceiverType,
                        extensionReceiverType = null,
                        returnType = renderType(call.symbol.returnType),
                        argumentTypes = requiredArgTypes,
                        line = lineOf(offset), column = colOf(offset),
                        endLine = endLineOf(expression.textRange.endOffset),
                        endColumn = endColOf(expression.textRange.endOffset),
                    )
                )
            }
        }

        // Property reads: list.size, map.keys, string.length, etc.
        // Captured as call sites with empty argumentTypes so signatures like
        // "kotlin.collections.Collection#size()" and "_#size()" match them.
        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            super.visitSimpleNameExpression(expression)
            // Skip names that are the callee of a call expression (already captured above).
            if (expression.parent is KtCallExpression) return
            val call = expression.resolveToCall()?.successfulCallOrNull<KaSimpleVariableAccessCall>() ?: return
            val symbol = call.symbol as? KaPropertySymbol ?: return
            val dispatch = call.partiallyAppliedSymbol.dispatchReceiver?.type?.let { renderType(it) }
            val extension = call.partiallyAppliedSymbol.extensionReceiver?.type?.let { renderType(it) }
            // Only record reads with a dispatch or extension receiver (i.e. qualified access).
            if (dispatch == null && extension == null) return
            val calleeFqName = symbol.callableId?.asSingleFqName()?.asString() ?: return
            val offset = expression.textRange.startOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = calleeFqName,
                    dispatchReceiverType = dispatch,
                    extensionReceiverType = extension,
                    returnType = renderType(symbol.returnType),
                    argumentTypes = emptyList(),
                    line = lineOf(offset), column = colOf(offset),
                    endLine = endLineOf(expression.textRange.endOffset),
                    endColumn = endColOf(expression.textRange.endOffset),
                )
            )
        }
    })

    return calls
}
