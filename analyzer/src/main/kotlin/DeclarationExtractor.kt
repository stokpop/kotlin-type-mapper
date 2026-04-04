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

import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

/**
 * Returns the start offset of a declaration, skipping any leading KDoc comment.
 * KDoc is a child of the PSI node and is included in [textRange], so we use
 * [KtDeclaration.modifierList] (first annotation/modifier) or the first non-KDoc
 * child as the start offset instead.
 */
private fun KtDeclaration.startOffsetSkippingKdoc(): Int =
    modifierList?.textRange?.startOffset
        ?: children.firstOrNull { it !is KDoc }?.textRange?.startOffset
        ?: textRange.startOffset

private fun Annotations.toAstList(): List<AnnotationAst> =
    mapNotNull { ann ->
        val fqn = ann.fqName?.asString() ?: return@mapNotNull null
        AnnotationAst(
            fqName = fqn,
            arguments = ann.allValueArguments.values.map { it.toString() },
        )
    }

/** Extracts all typed declarations from a single [KtFile] via [BindingContext]. */
fun extractDeclarations(ktFile: KtFile, bindingContext: BindingContext): List<DeclarationAst> {
    val declarations = mutableListOf<DeclarationAst>()
    val doc = ktFile.viewProvider.document

    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    ktFile.accept(object : KtTreeVisitorVoid() {

        override fun visitEnumEntry(enumEntry: KtEnumEntry) {
            super.visitEnumEntry(enumEntry)
            val descriptor = bindingContext[BindingContext.CLASS, enumEntry] ?: return
            val offset = enumEntry.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "enum_entry",
                    name = enumEntry.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.containingDeclaration.fqNameSafe.asString(),
                    annotations = descriptor.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitClass(klass: KtClass) {
            super.visitClass(klass)
            if (klass is KtEnumEntry) return   // handled by visitEnumEntry
            val descriptor = bindingContext[BindingContext.CLASS, klass] ?: return
            val offset = klass.startOffsetSkippingKdoc()
            val kind = when {
                klass.isEnum()       -> "enum"
                klass.isInterface()  -> "interface"
                klass.isAnnotation() -> "annotation"
                klass.isData()       -> "data_class"
                klass.isSealed()     -> "sealed_class"
                else                 -> "class"
            }
            declarations.add(
                DeclarationAst(
                    kind = kind,
                    name = klass.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    annotations = descriptor.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
            super.visitObjectDeclaration(declaration)
            val descriptor = bindingContext[BindingContext.CLASS, declaration] ?: return
            val offset = declaration.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = if (declaration.isCompanion()) "companion_object" else "object",
                    name = declaration.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    annotations = descriptor.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // Primary constructor val/var parameters become class properties;
        // lambda { x: Foo -> ... } explicitly typed parameters also captured here.
        override fun visitParameter(parameter: KtParameter) {
            super.visitParameter(parameter)
            val offset = parameter.textRange.startOffset
            when {
                parameter.hasValOrVar() -> {
                    val descriptor = bindingContext[BindingContext.PRIMARY_CONSTRUCTOR_PARAMETER, parameter] ?: return
                    declarations.add(DeclarationAst(
                        kind = "property",
                        name = parameter.name ?: "<anonymous>",
                        fqName = descriptor.fqNameSafe.asString(),
                        containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                        type = descriptor.type.toFqString(),
                        line = lineOf(offset), column = colOf(offset),
                    ))
                }
                parameter.typeReference != null && parameter.parent?.parent is KtFunctionLiteral -> {
                    val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, parameter] ?: return
                    declarations.add(DeclarationAst(
                        kind = "lambda_parameter",
                        name = parameter.name ?: "<anonymous>",
                        fqName = descriptor.fqNameSafe.asString(),
                        containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                        type = descriptor.type.toFqString(),
                        line = lineOf(offset), column = colOf(offset),
                    ))
                }
                // for-loop and catch params handled by visitForExpression / visitCatchSection;
                // named function params already included in the function's parameters list.
            }
        }

        override fun visitForExpression(expression: KtForExpression) {
            super.visitForExpression(expression)
            val param = expression.loopParameter ?: return
            val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, param] ?: return
            val offset = param.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "for_loop_variable",
                    name = param.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // catch (e: IOException) — exception variable
        override fun visitCatchSection(catchClause: KtCatchClause) {
            super.visitCatchSection(catchClause)
            val param = catchClause.catchParameter ?: return
            val descriptor = bindingContext[BindingContext.VALUE_PARAMETER, param] ?: return
            val offset = param.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "catch_variable",
                    name = param.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // val (a, b) = pair — destructuring entries
        override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
            super.visitDestructuringDeclarationEntry(entry)
            val descriptor = bindingContext[BindingContext.VARIABLE, entry] ?: return
            val offset = entry.textRange.startOffset
            declarations.add(
                DeclarationAst(
                    kind = "destructured_variable",
                    name = entry.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // typealias Foo = Bar<Baz>
        override fun visitTypeAlias(typeAlias: KtTypeAlias) {
            super.visitTypeAlias(typeAlias)
            val descriptor = bindingContext[BindingContext.TYPE_ALIAS, typeAlias] ?: return
            val offset = typeAlias.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = "typealias",
                    name = typeAlias.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.expandedType.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // secondary constructor(x: Foo, y: Bar)
        override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
            super.visitSecondaryConstructor(constructor)
            val descriptor = bindingContext[BindingContext.CONSTRUCTOR, constructor] ?: return
            val offset = constructor.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = "constructor",
                    name = constructor.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    returnType = descriptor.returnType.toFqString(),
                    parameters = descriptor.valueParameters.map { p ->
                        ParameterAst(name = p.name.asString(), type = p.type.toFqString())
                    },
                    annotations = descriptor.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            super.visitNamedFunction(function)
            val descriptor = bindingContext[BindingContext.FUNCTION, function] ?: return
            val offset = function.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = "function",
                    name = function.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    returnType = descriptor.returnType?.toFqString() ?: "?",
                    parameters = descriptor.valueParameters.map { p ->
                        ParameterAst(name = p.name.asString(), type = p.type.toFqString())
                    },
                    annotations = descriptor.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitProperty(property: KtProperty) {
            super.visitProperty(property)
            val descriptor = bindingContext[BindingContext.VARIABLE, property] ?: return
            val offset = property.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = "property",
                    name = property.name ?: "<anonymous>",
                    fqName = descriptor.fqNameSafe.asString(),
                    containingDeclaration = descriptor.containingDeclaration.fqNameSafe.asString(),
                    type = descriptor.type.toFqString(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }
    })

    return declarations
}
