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
package nl.stokpop.typemapper.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import nl.stokpop.typemapper.model.TypeResolutionMode
import nl.stokpop.typemapper.model.TypedAst
import nl.stokpop.typemapper.model.TypedAstJson
import nl.stokpop.typemapper.model.callsMatchingLocated
import nl.stokpop.typemapper.model.callsMatchingPolymorphicLocated
import nl.stokpop.typemapper.model.callsWithTypeArgument
import nl.stokpop.typemapper.model.declarationsAnnotatedWith
import nl.stokpop.typemapper.model.declarationsWithTypeArgument
import nl.stokpop.typemapper.model.dispatchReceiverIsNullable
import nl.stokpop.typemapper.model.expandAlias
import nl.stokpop.typemapper.model.extensionReceiverIsNullable
import nl.stokpop.typemapper.model.implementorsOf
import nl.stokpop.typemapper.model.resolveTypeAlias
import nl.stokpop.typemapper.model.returnTypeIsNullable
import nl.stokpop.typemapper.model.typeAliasChainOf
import java.io.File

class QueryCommand : CliktCommand("query") {
    override fun help(context: Context) =
        "Query a saved JSON AST file. FILE is the path to the JSON output of 'analyze'."

    val file by argument("FILE", help = "Path to JSON AST file")

    override fun run() {
        currentContext.obj = TypedAstJson.load(File(file))
    }
}

class CallsCommand : CliktCommand("calls") {
    override fun help(context: Context) = "Find call sites matching SIG (static-type exact match)."

    val ast by requireObject<TypedAst>()
    val sig by argument("SIG", help = "Signature pattern, e.g. 'kotlin.String#trim()'")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    val nullableReceiver by option("--nullable-receiver",
        help = "Only show calls where the dispatch or extension receiver is nullable").flag()
    val nonNullReceiver by option("--non-null-receiver",
        help = "Only show calls where the dispatch or extension receiver is non-null").flag()

    override fun run() {
        var results = ast.callsMatchingLocated(sig)
        if (nullableReceiver) {
            results = results.filter { (_, c) -> c.dispatchReceiverIsNullable() || c.extensionReceiverIsNullable() }
        }
        if (nonNullReceiver) {
            results = results.filter { (_, c) -> !c.dispatchReceiverIsNullable() && !c.extensionReceiverIsNullable() }
        }
        results.forEach { (path, call) ->
            echo(call.format(path))
            echoContext(ast.sourceRoot, path, call.line, ctx ?: 3)
        }
    }
}

class CallsPolymorphicCommand : CliktCommand("calls-polymorphic") {
    override fun help(context: Context) =
        "Find call sites where the receiver is a subtype of the type in SIG."

    val ast by requireObject<TypedAst>()
    val sig by argument("SIG")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    val nullableReceiver by option("--nullable-receiver",
        help = "Only show calls where the dispatch or extension receiver is nullable").flag()
    val nonNullReceiver by option("--non-null-receiver",
        help = "Only show calls where the dispatch or extension receiver is non-null").flag()

    override fun run() {
        var results = ast.callsMatchingPolymorphicLocated(sig)
        if (nullableReceiver) {
            results = results.filter { (_, c) -> c.dispatchReceiverIsNullable() || c.extensionReceiverIsNullable() }
        }
        if (nonNullReceiver) {
            results = results.filter { (_, c) -> !c.dispatchReceiverIsNullable() && !c.extensionReceiverIsNullable() }
        }
        results.forEach { (path, call) ->
            echo(call.format(path))
            echoContext(ast.sourceRoot, path, call.line, ctx ?: 3)
        }
    }
}

class ImplementorsCommand : CliktCommand("implementors") {
    override fun help(context: Context) =
        "Find class declarations that extend or implement INTERFACE_FQN."

    val ast by requireObject<TypedAst>()
    val fqn by argument("INTERFACE_FQN")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    val resolutionMode by option(
        "--type-resolution-mode", "-m",
        help = "How to handle types whose jar is absent: strict (default), lenient-warn, lenient-quiet"
    ).enum<TypeResolutionMode>(ignoreCase = true).default(TypeResolutionMode.STRICT)

    override fun run() {
        val results = if (resolutionMode == TypeResolutionMode.STRICT) {
            ast.implementorsOf(fqn)
        } else {
            ast.implementorsOf(fqn, resolutionMode) { warning -> echo("Warning: $warning", err = true) }
        }
        results.forEach { decl ->
            val path = ast.files.firstOrNull { f -> f.declarations.any { it.fqName == decl.fqName } }?.relativePath ?: ""
            echo(decl.format(path))
            echoContext(ast.sourceRoot, path, decl.line, ctx ?: 3)
        }
    }
}

class AnnotatedWithCommand : CliktCommand("annotated-with") {
    override fun help(context: Context) = "Find declarations carrying ANNOTATION_FQN."

    val ast by requireObject<TypedAst>()
    val fqn by argument("ANNOTATION_FQN")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    override fun run() = ast.declarationsAnnotatedWith(fqn).forEach { decl ->
        val path = ast.files.firstOrNull { f -> f.declarations.any { it.fqName == decl.fqName } }?.relativePath ?: ""
        echo(decl.format(path))
        echoContext(ast.sourceRoot, path, decl.line, ctx ?: 3)
    }
}

class ResolveAliasCommand : CliktCommand("resolve-alias") {
    override fun help(context: Context) =
        "Show the concrete type that TYPE_ALIAS_FQN expands to, or report if it is not a known alias."

    val ast by requireObject<TypedAst>()
    val fqn by argument("TYPE_ALIAS_FQN", help = "Fully-qualified typealias name, e.g. 'com.example.MyDog'")

    override fun run() {
        val chain = ast.typeAliasChainOf(fqn)
        if (chain.isEmpty()) {
            echo("$fqn is not a known typealias in this AST.")
            return
        }
        echo(chain.joinToString(" -> "))
        echo("Expanded: ${ast.expandAlias(fqn)}")
    }
}

class UnresolvedReferencesCommand : CliktCommand("unresolved-references") {
    override fun help(context: Context) =
        "List unresolved references found during analysis (named types and wildcard imports from unknown packages)."

    val ast by requireObject<TypedAst>()
    val ctx by option("--context", "-C", help = "Source lines of context (default: 0, off)").int()

    override fun run() {
        var count = 0
        for (file in ast.files) {
            for (ref in file.unresolvedReferences) {
                val loc = "${file.relativePath}:${ref.line}:${ref.column}"
                echo("$loc  ${ref.name}")
                echoContext(ast.sourceRoot, file.relativePath, ref.line, ctx ?: 0)
                count++
            }
        }
        if (count == 0) echo("No unresolved references found.")
    }
}

class UnresolvedTypesCommand : CliktCommand("unresolved-types") {
    override fun help(context: Context) =
        "List declarations whose type, return type, or parameter types could not be resolved (missing jar)."

    val ast by requireObject<TypedAst>()
    val ctx by option("--context", "-C", help = "Source lines of context (default: 0, off)").int()

    override fun run() {
        var count = 0
        for (file in ast.files) {
            for (decl in file.declarations) {
                val unresolvedTypes = buildList {
                    decl.type?.takeIf { it.isUnresolved }?.let { add(it) }
                    decl.returnType?.takeIf { it.isUnresolved }?.let { add(it) }
                    decl.parameters.filter { it.type.isUnresolved }.forEach { add(it.type) }
                }
                if (unresolvedTypes.isNotEmpty()) {
                    val loc = "${file.relativePath}:${decl.line}:${decl.column}"
                    val types = unresolvedTypes.joinToString(", ") { "${it.simpleName} (fqName: ${it.fqName})" }
                    echo("$loc  ${decl.kind}  ${decl.fqName}  [$types]")
                    echoContext(ast.sourceRoot, file.relativePath, decl.line, ctx ?: 0)
                    count++
                }
            }
        }
        if (count == 0) echo("No unresolved types found.")
    }
}

class TypeArgUsesCommand : CliktCommand("type-arg-uses") {
    override fun help(context: Context) =
        "Find declarations and call sites where FQN appears as a type argument (e.g. List<FQN>, Map<_, FQN>)."

    val ast by requireObject<TypedAst>()
    val fqn by argument("FQN", help = "Fully-qualified type name to search for in type arguments")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 0, off)").int()

    override fun run() {
        val matchedDecls = ast.declarationsWithTypeArgument(fqn)
        val matchedCalls = ast.callsWithTypeArgument(fqn)
        var count = 0
        for (file in ast.files) {
            for (decl in matchedDecls.filter { d ->
                file.declarations.any { it.fqName == d.fqName }
            }) {
                echo(decl.format(file.relativePath))
                echoContext(ast.sourceRoot, file.relativePath, decl.line, ctx ?: 0)
                count++
            }
            for (call in matchedCalls.filter { c -> c in file.calls }) {
                echo(call.format(file.relativePath))
                echoContext(ast.sourceRoot, file.relativePath, call.line, ctx ?: 0)
                count++
            }
        }
        if (count == 0) echo("No uses of '$fqn' as a type argument found.")
    }
}
