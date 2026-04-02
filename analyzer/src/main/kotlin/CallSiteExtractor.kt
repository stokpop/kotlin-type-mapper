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
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

/** Extracts all resolved call sites from a single [KtFile] via [BindingContext]. */
fun extractCallSites(ktFile: KtFile, bindingContext: BindingContext): List<CallSiteAst> {
    val calls = mutableListOf<CallSiteAst>()
    val doc = ktFile.viewProvider.document

    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    ktFile.accept(object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
            super.visitCallExpression(expression)
            val resolvedCall = expression.getResolvedCall(bindingContext) ?: return
            val descriptor = resolvedCall.resultingDescriptor
            val offset = expression.textRange.startOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = descriptor.fqNameSafe.asString(),
                    dispatchReceiverType = resolvedCall.dispatchReceiver?.type?.toFqString(),
                    extensionReceiverType = resolvedCall.extensionReceiver?.type?.toFqString(),
                    returnType = descriptor.returnType?.toFqString() ?: "kotlin.Unit",
                    argumentTypes = descriptor.valueParameters.map { it.type.toFqString() },
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // Property reads: list.size, map.keys, string.length, etc.
        // Captured as call sites with empty argumentTypes so signatures like
        // "kotlin.collections.Collection#size()" and "_#size()" match them.
        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            super.visitSimpleNameExpression(expression)
            // Skip names that are the callee of a call expression (already captured above).
            if (expression.parent is KtCallExpression) return
            val resolvedCall = expression.getResolvedCall(bindingContext) ?: return
            val descriptor = resolvedCall.resultingDescriptor as? PropertyDescriptor ?: return
            // Only record reads with a dispatch or extension receiver (i.e. qualified access).
            val dispatch   = resolvedCall.dispatchReceiver?.type?.toFqString()
            val extension  = resolvedCall.extensionReceiver?.type?.toFqString()
            if (dispatch == null && extension == null) return
            val offset = expression.textRange.startOffset
            calls.add(
                CallSiteAst(
                    calleeFqName = descriptor.fqNameSafe.asString(),
                    dispatchReceiverType = dispatch,
                    extensionReceiverType = extension,
                    returnType = descriptor.type.toFqString(),
                    argumentTypes = emptyList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }
    })

    return calls
}
