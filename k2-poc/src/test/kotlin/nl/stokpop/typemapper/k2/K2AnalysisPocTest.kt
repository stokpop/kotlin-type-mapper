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
package nl.stokpop.typemapper.k2

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForDebug
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 1 PoC: verifies that the K2 Analysis API can be bootstrapped standalone
 * and resolve function return types and property types from a simple Kotlin source file.
 *
 * This test does NOT use the existing K1 pipeline; it is a clean K2 experiment.
 * Once the full K2 migration is complete (Phase 6), this test becomes part of the
 * normal test suite and the K1 test equivalents can be removed.
 */
@OptIn(KaExperimentalApi::class)
internal class K2AnalysisPocTest {

    /**
     * Write a small Kotlin source file to a temp directory, analyse it with the K2
     * Analysis API, and assert that function return types and property types resolve
     * to the expected fully-qualified names.
     */
    @Test
    fun `K2 Analysis API resolves function return types and property types`() {
        val sourceCode = """
            package poc

            class Person(val name: String, val age: Int)

            fun greeting(person: Person): String = "Hello, ${'$'}{person.name}"

            fun ages(persons: List<Person>): List<Int> = persons.map { it.age }

            val defaultPerson: Person = Person("Alice", 30)
        """.trimIndent()

        val tempDir = Files.createTempDirectory("k2-poc-").toFile()
        try {
            tempDir.resolve("Source.kt").writeText(sourceCode)
            val results = analyzeWithK2(tempDir)

            // Function: greeting(Person): String
            val greeting = results.functions["greeting"]
            assertTrue(greeting != null, "Expected function 'greeting' to be found")
            assertEquals("kotlin.String", greeting, "Expected greeting return type to be kotlin.String")

            // Function: ages(List<Person>): List<Int>
            val ages = results.functions["ages"]
            assertTrue(ages != null, "Expected function 'ages' to be found")
            assertTrue(ages!!.contains("List"), "Expected ages return type to contain 'List', was: $ages")

            // Property: defaultPerson: Person
            val defaultPerson = results.properties["defaultPerson"]
            assertTrue(defaultPerson != null, "Expected property 'defaultPerson' to be found")
            assertEquals("poc.Person", defaultPerson, "Expected defaultPerson type to be poc.Person")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------
    // Minimal K2 analysis harness (standalone, no IntelliJ, no K1)
    // ---------------------------------------------------------------------------

    private data class AnalysisResults(
        val functions: Map<String, String>,   // name -> return type FQN
        val properties: Map<String, String>,  // name -> type FQN
    )

    private fun analyzeWithK2(sourceRoot: File): AnalysisResults {
        val disposable = Disposer.newDisposable("K2AnalysisPocTest")
        try {
            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform

                    val jdkModule = buildKtSdkModule {
                        addBinaryRootsFromJdkHome(
                            java.nio.file.Paths.get(System.getProperty("java.home")),
                            isJre = false,
                        )
                        libraryName = "JDK"
                        platform = JvmPlatforms.defaultJvmPlatform
                    }

                    val stdlibJar = Unit::class.java.protectionDomain?.codeSource?.location
                        ?.toURI()?.let { File(it) }
                    val stdlibModule = if (stdlibJar != null && stdlibJar.exists()) {
                        buildKtLibraryModule {
                            libraryName = "kotlin-stdlib"
                            addBinaryRoot(stdlibJar.toPath())
                            platform = JvmPlatforms.defaultJvmPlatform
                        }
                    } else null

                    addModule(buildKtSourceModule {
                        moduleName = "poc"
                        platform = JvmPlatforms.defaultJvmPlatform
                        addSourceRoot(sourceRoot.toPath())
                        addRegularDependency(jdkModule)
                        stdlibModule?.let { addRegularDependency(it) }
                    })
                }
            }

            val ktFiles = session.modulesWithFiles.values
                .flatten()
                .filterIsInstance<KtFile>()

            val functions = mutableMapOf<String, String>()
            val properties = mutableMapOf<String, String>()

            for (ktFile in ktFiles) {
                for (declaration in ktFile.declarations) {
                    when (declaration) {
                        is KtNamedFunction -> analyze(declaration) {
                            val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return@analyze
                            val name = symbol.name.asString()
                            val typeString = symbol.returnType.render(
                                KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                                Variance.INVARIANT,
                            )
                            functions[name] = typeString
                        }
                        is KtProperty -> analyze(declaration) {
                            val symbol = declaration.symbol as? KaPropertySymbol ?: return@analyze
                            val name = symbol.name.asString()
                            val typeString = symbol.returnType.render(
                                KaTypeRendererForDebug.WITH_QUALIFIED_NAMES,
                                Variance.INVARIANT,
                            )
                            properties[name] = typeString
                        }
                    }
                }
            }

            return AnalysisResults(functions = functions, properties = properties)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
