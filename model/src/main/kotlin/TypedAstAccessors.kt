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
package nl.stokpop.typemapper.model

fun TypedAst.declarations(): List<DeclarationAst> = files.flatMap { it.declarations }
fun TypedAst.calls(): List<CallSiteAst> = files.flatMap { it.calls }
fun TypedAst.functions(): List<DeclarationAst> = declarations().filter { it.kind == "function" }
fun TypedAst.classes(): List<DeclarationAst> = declarations().filter { it.kind in CLASS_KINDS }
fun TypedAst.properties(): List<DeclarationAst> = declarations().filter { it.kind == "property" }
fun TypedAst.fileByPath(relativePath: String): FileAst? =
    files.firstOrNull { it.relativePath == relativePath }
