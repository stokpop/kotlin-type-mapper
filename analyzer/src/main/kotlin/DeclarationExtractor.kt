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

import nl.stokpop.typemapper.model.AnnotationAst
import nl.stokpop.typemapper.model.DeclarationAst
import nl.stokpop.typemapper.model.DeclarationKind
import nl.stokpop.typemapper.model.ParameterAst
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationList
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFileSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
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

private fun KtDeclaration.startOffsetSkippingKdoc(): Int =
    modifierList?.textRange?.startOffset
        ?: children.firstOrNull { it !is KDoc }?.textRange?.startOffset
        ?: textRange.startOffset

@OptIn(KaExperimentalApi::class)
internal fun KaSession.extractDeclarations(ktFile: KtFile): List<DeclarationAst> {
    val declarations = mutableListOf<DeclarationAst>()
    val doc = ktFile.viewProvider.document

    fun lineOf(offset: Int) = (doc?.getLineNumber(offset) ?: 0) + 1
    fun colOf(offset: Int): Int {
        val line = doc?.getLineNumber(offset) ?: return 1
        return offset - (doc.getLineStartOffset(line)) + 1
    }

    fun callableFqName(symbol: KaCallableSymbol): String =
        symbol.callableId?.asSingleFqName()?.asString()
            ?: (symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "") + "." +
                ((symbol as? KaNamedSymbol)?.name?.asString() ?: "<anonymous>")

    fun variableDeclaration(
        kind: DeclarationKind,
        name: String,
        symbol: KaVariableSymbol,
        offset: Int,
    ): DeclarationAst = DeclarationAst(
        kind = kind,
        name = name,
        fqName = callableFqName(symbol),
        containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
        type = renderType(symbol.returnType),
        line = lineOf(offset),
        column = colOf(offset),
    )

    ktFile.accept(object : KtTreeVisitorVoid() {
        override fun visitEnumEntry(enumEntry: KtEnumEntry) {
            super.visitEnumEntry(enumEntry)
            val symbol = enumEntry.symbol as? KaEnumEntrySymbol ?: return
            val offset = enumEntry.textRange.startOffset
            val containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: ""
            val fqName = symbol.callableId?.asSingleFqName()?.asString()
                ?: listOf(containingDeclaration, enumEntry.name ?: "<anonymous>")
                    .filter { it.isNotEmpty() }
                    .joinToString(".")
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.ENUM_ENTRY,
                    name = enumEntry.name ?: "<anonymous>",
                    fqName = fqName,
                    containingDeclaration = containingDeclaration,
                    type = containingDeclaration,
                    annotations = symbol.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitClass(klass: KtClass) {
            super.visitClass(klass)
            if (klass is KtEnumEntry) return   // handled by visitEnumEntry
            val symbol = klass.symbol as? KaClassSymbol ?: return
            val offset = klass.startOffsetSkippingKdoc()
            val kind = when {
                klass.isEnum() -> DeclarationKind.ENUM
                klass.isInterface() -> DeclarationKind.INTERFACE
                klass.isAnnotation() -> DeclarationKind.ANNOTATION
                klass.isData() -> DeclarationKind.DATA_CLASS
                klass.isSealed() -> DeclarationKind.SEALED_CLASS
                klass.isValue() -> DeclarationKind.VALUE_CLASS
                else -> DeclarationKind.CLASS
            }
            val superTypes = symbol.superTypes.map { renderType(it).substringBefore('?') }
            declarations.add(
                DeclarationAst(
                    kind = kind,
                    name = klass.name ?: "<anonymous>",
                    fqName = symbol.classId?.asSingleFqName()?.asString() ?: klass.name ?: "<anonymous>",
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    annotations = symbol.annotations.toAstList(),
                    superTypes = superTypes,
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
            super.visitObjectDeclaration(declaration)
            val symbol = declaration.symbol as? KaClassSymbol ?: return
            val offset = declaration.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = when {
                        declaration.isCompanion() -> DeclarationKind.COMPANION_OBJECT
                        declaration.isData() -> DeclarationKind.DATA_OBJECT
                        else -> DeclarationKind.OBJECT
                    },
                    name = declaration.name ?: "<anonymous>",
                    fqName = symbol.classId?.asSingleFqName()?.asString() ?: declaration.name ?: "<anonymous>",
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    annotations = symbol.annotations.toAstList(),
                    superTypes = symbol.superTypes.map { renderType(it).substringBefore('?') },
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
                    val symbol = parameter.symbol as? KaPropertySymbol ?: return
                    declarations.add(
                        DeclarationAst(
                            kind = DeclarationKind.PROPERTY,
                            name = parameter.name ?: "<anonymous>",
                            fqName = callableFqName(symbol),
                            containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                            type = renderType(symbol.returnType),
                            line = lineOf(offset),
                            column = colOf(offset),
                        )
                    )
                }
                parameter.typeReference != null && parameter.parent?.parent is KtFunctionLiteral -> {
                    val symbol = parameter.symbol as? KaValueParameterSymbol ?: return
                    declarations.add(
                        DeclarationAst(
                            kind = DeclarationKind.LAMBDA_PARAMETER,
                            name = parameter.name ?: "<anonymous>",
                            fqName = callableFqName(symbol),
                            containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                            type = renderType(symbol.returnType),
                            line = lineOf(offset),
                            column = colOf(offset),
                        )
                    )
                }
                // for-loop and catch params handled by visitForExpression / visitCatchSection;
                // named function params already included in the function's parameters list.
            }
        }

        override fun visitForExpression(expression: KtForExpression) {
            super.visitForExpression(expression)
            val param = expression.loopParameter ?: return
            val symbol = param.symbol as? KaValueParameterSymbol ?: return
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.FOR_LOOP_VARIABLE,
                    name = param.name ?: "<anonymous>",
                    fqName = callableFqName(symbol),
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    type = renderType(symbol.returnType),
                    line = lineOf(param.textRange.startOffset),
                    column = colOf(param.textRange.startOffset),
                )
            )
        }

        // catch (e: IOException) — exception variable
        override fun visitCatchSection(catchClause: KtCatchClause) {
            super.visitCatchSection(catchClause)
            val param = catchClause.catchParameter ?: return
            val symbol = param.symbol as? KaValueParameterSymbol ?: return
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.CATCH_VARIABLE,
                    name = param.name ?: "<anonymous>",
                    fqName = callableFqName(symbol),
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    type = renderType(symbol.returnType),
                    line = lineOf(param.textRange.startOffset),
                    column = colOf(param.textRange.startOffset),
                )
            )
        }

        // val (a, b) = pair — destructuring entries
        override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
            super.visitDestructuringDeclarationEntry(entry)
            val symbol = entry.symbol as? KaVariableSymbol ?: return
            declarations.add(variableDeclaration(
                kind = DeclarationKind.DESTRUCTURED_VARIABLE,
                name = entry.name ?: "<anonymous>",
                symbol = symbol,
                offset = entry.textRange.startOffset,
            ))
        }

        // typealias Foo = Bar<Baz>
        override fun visitTypeAlias(typeAlias: KtTypeAlias) {
            super.visitTypeAlias(typeAlias)
            val symbol = typeAlias.symbol as? KaTypeAliasSymbol ?: return
            val offset = typeAlias.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.TYPEALIAS,
                    name = typeAlias.name ?: "<anonymous>",
                    fqName = symbol.classId?.asSingleFqName()?.asString()
                        ?: (symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "") + "." + ((symbol as? KaNamedSymbol)?.name?.asString() ?: "<anonymous>"),
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    type = renderType(symbol.expandedType),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        // secondary constructor(x: Foo, y: Bar)
        override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
            super.visitSecondaryConstructor(constructor)
            val symbol = constructor.symbol as? KaConstructorSymbol ?: return
            val offset = constructor.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.CONSTRUCTOR,
                    name = constructor.name ?: "<anonymous>",
                    fqName = callableFqName(symbol),
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    returnType = renderType(symbol.returnType),
                    parameters = symbol.valueParameters.map { ParameterAst(name = it.name.asString(), type = renderType(it.returnType)) },
                    annotations = symbol.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            super.visitNamedFunction(function)
            val symbol = function.symbol as? KaNamedFunctionSymbol ?: return
            val offset = function.startOffsetSkippingKdoc()
            declarations.add(
                DeclarationAst(
                    kind = DeclarationKind.FUNCTION,
                    name = function.name ?: "<anonymous>",
                    fqName = callableFqName(symbol),
                    containingDeclaration = symbol.containingDeclaration?.let { containingSymbolFqName(it) } ?: "",
                    returnType = renderType(symbol.returnType),
                    parameters = symbol.valueParameters.map { ParameterAst(name = it.name.asString(), type = renderType(it.returnType)) },
                    annotations = symbol.annotations.toAstList(),
                    line = lineOf(offset),
                    column = colOf(offset),
                )
            )
        }

        override fun visitProperty(property: KtProperty) {
            super.visitProperty(property)
            val symbol = property.symbol as? KaVariableSymbol ?: return
            declarations.add(variableDeclaration(
                kind = DeclarationKind.PROPERTY,
                name = property.name ?: "<anonymous>",
                symbol = symbol,
                offset = property.startOffsetSkippingKdoc(),
            ))
        }
    })

    return declarations
}

private fun KaSession.containingSymbolFqName(symbol: KaDeclarationSymbol): String = when (symbol) {
    is KaClassSymbol -> symbol.classId?.asSingleFqName()?.asString() ?: (symbol as? KaNamedSymbol)?.name?.asString() ?: ""
    is KaCallableSymbol -> symbol.callableId?.asSingleFqName()?.asString() ?: (symbol as? KaNamedSymbol)?.name?.asString() ?: ""
    is KaFileSymbol -> (symbol.psi as? KtFile)?.packageFqName?.asString() ?: ""
    else -> ""
}

private fun KaAnnotationList.toAstList(): List<AnnotationAst> = mapNotNull { ann ->
    val fqn = ann.classId?.asSingleFqName()?.asString() ?: return@mapNotNull null
    AnnotationAst(fqName = fqn, arguments = ann.arguments.map { it.expression.toString() })
}
