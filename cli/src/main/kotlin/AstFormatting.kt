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
import nl.stokpop.typemapper.model.CallSiteAst
import nl.stokpop.typemapper.model.DeclarationAst
import java.io.File

fun CallSiteAst.format(filePath: String = ""): String {
    val receiver = dispatchReceiverType ?: extensionReceiverType ?: "<no-receiver>"
    val params = argumentTypes.joinToString(", ")
    val loc = if (filePath.isNotEmpty()) "$filePath:$line:$column" else "$line:$column"
    return "$loc  $receiver → $calleeFqName($params): $returnType"
}

fun DeclarationAst.format(filePath: String = ""): String {
    val typeInfo = returnType ?: type ?: kind
    val loc = if (filePath.isNotEmpty()) "$filePath:$line:$column" else "$line:$column"
    return "$loc  $kind  $fqName  [$typeInfo]"
}

/**
 * Prints [contextLines] lines before and after [targetLine] (1-based) from the source file
 * at [sourceRoot]/[relativePath]. The matching line is prefixed with ">". No-op if the
 * file cannot be read or [contextLines] is 0.
 */
fun CliktCommand.echoContext(sourceRoot: String, relativePath: String, targetLine: Int, contextLines: Int) {
    if (contextLines <= 0 || relativePath.isEmpty()) return
    val file = File(sourceRoot).resolve(relativePath)
    if (!file.isFile) return
    val lines = file.readLines()
    val first = (targetLine - 1 - contextLines).coerceAtLeast(0)
    val last  = (targetLine - 1 + contextLines).coerceAtMost(lines.size - 1)
    for (i in first..last) {
        val marker = if (i == targetLine - 1) ">" else " "
        echo("  $marker ${"%4d".format(i + 1)}: ${lines[i]}")
    }
    echo("")
}
