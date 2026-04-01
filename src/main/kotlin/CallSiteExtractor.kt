import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
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
    })

    return calls
}
