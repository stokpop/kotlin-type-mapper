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
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import nl.stokpop.typemapper.model.TypedAst
import nl.stokpop.typemapper.model.TypedAstJson
import nl.stokpop.typemapper.model.callsMatchingLocated
import nl.stokpop.typemapper.model.callsMatchingPolymorphicLocated
import nl.stokpop.typemapper.model.declarationsAnnotatedWith
import nl.stokpop.typemapper.model.implementorsOf
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
    override fun run() = ast.callsMatchingLocated(sig).forEach { (path, call) ->
        echo(call.format(path))
        echoContext(ast.sourceRoot, path, call.line, ctx ?: 3)
    }
}

class CallsPolymorphicCommand : CliktCommand("calls-polymorphic") {
    override fun help(context: Context) =
        "Find call sites where the receiver is a subtype of the type in SIG."

    val ast by requireObject<TypedAst>()
    val sig by argument("SIG")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    override fun run() = ast.callsMatchingPolymorphicLocated(sig).forEach { (path, call) ->
        echo(call.format(path))
        echoContext(ast.sourceRoot, path, call.line, ctx ?: 3)
    }
}

class ImplementorsCommand : CliktCommand("implementors") {
    override fun help(context: Context) =
        "Find class declarations that extend or implement INTERFACE_FQN."

    val ast by requireObject<TypedAst>()
    val fqn by argument("INTERFACE_FQN")
    val ctx by option("--context", "-C", help = "Source lines of context (default: 3, 0 = off)").int()
    override fun run() = ast.implementorsOf(fqn).forEach { decl ->
        val path = ast.files.firstOrNull { f -> f.declarations.any { it.fqName == decl.fqName } }?.relativePath ?: ""
        echo(decl.format(path))
        echoContext(ast.sourceRoot, path, decl.line, ctx ?: 3)
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
