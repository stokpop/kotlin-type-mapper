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
import com.github.ajalt.clikt.parameters.arguments.argument
import nl.stokpop.typemapper.model.TypedAstJson
import nl.stokpop.typemapper.model.calls
import nl.stokpop.typemapper.model.declarations
import java.io.File

class LoadCommand : CliktCommand("load") {
    override fun help(context: Context) = "Load a JSON AST file and print a summary."

    val file by argument("FILE", help = "Path to JSON AST file")

    override fun run() {
        val ast = TypedAstJson.load(File(file))
        echo("Schema version  : ${ast.schemaVersion}")
        echo("Source root     : ${ast.sourceRoot}")
        echo("Files           : ${ast.files.size}")
        echo("Declarations    : ${ast.declarations().size}")
        echo("Call sites      : ${ast.calls().size}")
        echo("Type hierarchy  : ${ast.typeHierarchy.size} types")
    }
}
