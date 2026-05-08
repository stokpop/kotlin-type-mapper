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

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    TypeMapperCli()
        .subcommands(
            AnalyzeCommand(),
            LoadCommand(),
            QueryCommand().subcommands(
                CallsCommand(),
                CallsPolymorphicCommand(),
                ImplementorsCommand(),
                AnnotatedWithCommand(),
                UnresolvedReferencesCommand(),
            )
        )
        .main(args)
    // buildStandaloneAnalysisAPISession starts non-daemon IntelliJ threads
    // (AppDelayQueue$TransferThread, ThreadPoolExecutor$Worker) that survive
    // Disposer.dispose() and prevent normal JVM exit. There is no public API
    // to shut them down — see https://youtrack.jetbrains.com/issue/KT-73127
    exitProcess(0)
}
