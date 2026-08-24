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

import nl.stokpop.typemapper.model.*

import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

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

/** Extracts all resolved call sites from a single [KtFile] via [BindingContext]. */
fun extractCallSites(ktFile: KtFile, bindingContext: BindingContext, imports: List<String> = emptyList()): List<CallSiteAst> {
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
            val resolvedCall = expression.getResolvedCall(bindingContext) ?: return
            val descriptor = resolvedCall.resultingDescriptor
            val offset = expression.textRange.startOffset
            val extReceiverType = resolvedCall.extensionReceiver?.type?.toTypeAst(imports)
            val allArgTypes = descriptor.valueParameters.map { it.type.toTypeAst(imports) }
            val unitType = TypeAst(fqName = "kotlin.Unit", simpleName = "Unit")
            val endOffset = expression.textRange.endOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = descriptor.fqNameSafe.asString(),
                    dispatchReceiverType = resolvedCall.dispatchReceiver?.type?.toTypeAst(imports),
                    extensionReceiverType = extReceiverType,
                    returnType = descriptor.returnType?.toTypeAst(imports) ?: unitType,
                    argumentTypes = allArgTypes,
                    line = lineOf(offset), column = colOf(offset),
                    endLine = endLineOf(endOffset), endColumn = endColOf(endOffset),
                )
            )
            if (shouldSynthesizeJavaCallSite(extReceiverType?.fqName, descriptor.fqNameSafe.asString())) {
                val requiredArgTypes = descriptor.valueParameters
                    .filter { !it.declaresDefaultValue() }
                    .map { it.type.toTypeAst(imports) }
                calls.add(
                    CallSiteAst(
                        calleeFqName = descriptor.fqNameSafe.asString(),
                        dispatchReceiverType = extReceiverType,
                        extensionReceiverType = null,
                        returnType = descriptor.returnType?.toTypeAst(imports) ?: unitType,
                        argumentTypes = requiredArgTypes,
                        line = lineOf(offset), column = colOf(offset),
                        endLine = endLineOf(expression.textRange.endOffset),
                        endColumn = endColOf(expression.textRange.endOffset),
                    )
                )
            }
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            super.visitSimpleNameExpression(expression)
            if (expression.parent is KtCallExpression) return
            val resolvedCall = expression.getResolvedCall(bindingContext) ?: return
            val descriptor = resolvedCall.resultingDescriptor as? PropertyDescriptor ?: return
            val dispatch   = resolvedCall.dispatchReceiver?.type?.toTypeAst(imports)
            val extension  = resolvedCall.extensionReceiver?.type?.toTypeAst(imports)
            if (dispatch == null && extension == null) return
            val offset = expression.textRange.startOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = descriptor.fqNameSafe.asString(),
                    dispatchReceiverType = dispatch,
                    extensionReceiverType = extension,
                    returnType = descriptor.type.toTypeAst(imports),
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
