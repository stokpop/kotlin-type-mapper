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

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForDebug
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.types.Variance

/**
 * Renders a [KaType] as a fully-qualified string including generic arguments.
 * [KaType.fullyExpandedType] is used first so that type aliases (e.g. kotlin.Exception → java.lang.Exception)
 * are expanded before rendering, matching the behaviour of the K1 KotlinType.toFqString() helper.
 */
private val debugAliasComment = Regex("""[^<>,?\s]+ /\* = ([^*]+) \*/""")

@OptIn(KaExperimentalApi::class)
internal fun KaSession.renderType(kaType: KaType): String =
    kaType.fullyExpandedType
        .render(KaTypeRendererForDebug.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
        .replace(debugAliasComment, "$1")
