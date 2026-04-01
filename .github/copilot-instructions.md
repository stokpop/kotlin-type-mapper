# Copilot Instructions

## Project Purpose

TypeMapper scans Kotlin source files in a Maven or Gradle project and extracts variables, functions, and parameters with their types using the Kotlin compiler's embedded API for semantic (binding context) analysis.

## Build & Test Commands

```bash
# Build
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "nl.stokpop.memory.HistoReaderTest"

# Run a single test method
./gradlew test --tests "nl.stokpop.memory.HistoReaderTest.extractDateLegacy"

# Re-run tests even if up-to-date (Gradle caches test results)
./gradlew test --rerun
```

Tests use **JUnit 5 (Jupiter)** via `useJUnitPlatform()`.

## Architecture

```
src/main/kotlin/TypeMapper.kt     ← Core: compiler API integration (early stage)
test-projects/memory-check/       ← Standalone Gradle project used as a real-world
                                    Kotlin codebase for TypeMapper to analyze
```

**`TypeMapper.kt`** is the entry point. It creates a `KotlinCoreEnvironment` with a `CompilerConfiguration`, parses a `KtFile` (Kotlin PSI AST), performs semantic analysis via `BindingContext`, and returns extracted type information.

**`test-projects/memory-check/`** is a fully independent Gradle project — it has its own `build.gradle.kts`, `settings.gradle.kts`, and test suite. It is a heap-histogram memory-leak analysis tool with ~20 domain classes, making it a rich target for type extraction tests. Run its tests separately:

```bash
cd test-projects/memory-check && ./gradlew test
cd test-projects/memory-check && ./gradlew test --tests "nl.stokpop.memory.HistoReaderTest"
```

## Key Conventions

**Kotlin compiler API usage** — the root project depends on `kotlin-compiler-embeddable`. The pattern is:
1. Build a `CompilerConfiguration` with a custom `MessageCollector`
2. Create a `KotlinCoreEnvironment` via `createForProduction` or `createForTests`
3. Parse source into a `KtFile` using `KtPsiFactory`
4. Resolve types via `BindingContext` from `KotlinToJVMBytecodeCompiler` or analysis utilities

**test-projects code style** (representative real-world Kotlin to be analyzed):
- Domain types are `data class` or `sealed class`; singletons use `object`
- Companion objects hold factory methods and constants
- Collections are processed with functional chains (`map`, `filter`, `sortedBy`, `firstOrNull`, etc.)
- Nullability is handled via safe-navigation (`?.let`, `?.also`) rather than null checks
- All source files carry an Apache 2.0 license header (copyright: Peter Paul Bakker, Stokpop Software Solutions)
- Test classes use `internal` visibility and import assertions from `org.junit.jupiter.api.Assertions`

**Versions**: Kotlin 2.2.21, Gradle 9.4.1, JDK 21, JUnit Jupiter 5.13.4.
