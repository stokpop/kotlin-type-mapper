# Kotlin TypeMapper

Scans Kotlin source files in a Gradle or Maven project and extracts a semantic AST — all declarations (functions, classes, properties, etc.) and resolved call sites with full type information — serialised to JSON.

Analysis uses the Kotlin K1 compiler (embedded) so types are fully resolved, including generics and nullability. A reflection-based type hierarchy is built at analysis time so polymorphic queries work correctly.

## Subprojects

| Module | Artifact | Purpose |
|---|---|---|
| `:model` | `kotlin-type-mapper-model` | Data model, JSON I/O, query extensions, signature matcher |
| `:analyzer` | `kotlin-type-mapper-analyzer` | Compiler integration, PSI visitors, classpath resolution |
| `:cli` | *(shadow jar)* | Command-line tool (`analyze`, `load`, `query`) |

## Build

```bash
./gradlew build
```

If `test-projects/memory-check/` does not exist it is cloned automatically from [github.com/stokpop/memory-check](https://github.com/stokpop/memory-check).

## CLI usage

The shadow jar is built to `cli/build/libs/cli-<version>-all.jar`.

### Analyze a project

```bash
java -jar cli-0.1.0-all.jar analyze --output result.json /path/to/src/main/kotlin
```

Classpath jars are resolved automatically from the nearest Gradle or Maven build.

### Query from JSON

```bash
java -jar cli-0.1.0-all.jar query result.json calls "kotlin.String#trim()"
java -jar cli-0.1.0-all.jar query result.json calls-polymorphic "kotlin.collections.Collection#size()"
java -jar cli-0.1.0-all.jar query result.json implementors "java.io.Closeable"
java -jar cli-0.1.0-all.jar query result.json annotated-with "org.springframework.stereotype.Service"
```

All query commands accept `--context/-C <N>` (default 3) to show ±N source lines around each match. Pass `0` to suppress context.

```
nl/stokpop/memory/HistoReader.kt:86:21  kotlin.collections.List<kotlin.String> → kotlin.collections.List.size(): kotlin.Int
      83:         // 5 elements can be found in java 9+ dumps, example:
      84:         //    4:         88508        2124192  java.lang.String
      85:         // the module is ignored
  >   86:         if (!(split.size == 4 || split.size == 5)) {
      87:             throw InvalidHistoLineException("Cannot read histo line ...")
      88:         }
      89:         val lineNumber = skipLastCharacter(split[0])
```

### Or run analysis via Gradle (uses memory-check as demo target)

```bash
./gradlew run
```

## Signature patterns

Queries use PMD-style patterns:

```
receiverType#methodName(paramType, ...)
```

| Wildcard | Meaning |
|---|---|
| `_` | any single type or name |
| `*` | any parameter list |

```
kotlin.String#trim()       exact match
_#trim()                   any receiver
kotlin.String#_()          any method on String
kotlin.String#_(*)         any method, any params
```

## Library usage

Publish to local Maven and add `:model` + `:analyzer` as dependencies:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// build.gradle.kts
implementation("nl.stokpop.typemapper:kotlin-type-mapper-analyzer:0.1.0")
```

```kotlin
import nl.stokpop.typemapper.analyzer.analyzeKotlinProject
import nl.stokpop.typemapper.model.*

// Run analysis
val sourceRoot = File("/path/to/src/main/kotlin")
val ktFiles = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
val ast: TypedAst = analyzeKotlinProject(
    files = ktFiles,
    sourceRoot = sourceRoot,
    extraClasspath = listOf(File("mylib.jar"), File("build/classes/kotlin/main"))
)

// Or load from JSON
val ast: TypedAst = TypedAstJson.load(File("result.json"))

// Query
ast.callsMatching("_#size()").forEach { println(it) }
ast.callsMatchingPolymorphic("kotlin.collections.Collection#size()").forEach { println(it) }
ast.implementorsOf("java.io.Closeable").forEach { println(it) }
ast.declarationsAnnotatedWith("kotlin.Deprecated").forEach { println(it) }
```

## Output format

```json
{
  "schemaVersion": "1.3",
  "generatedBy": "kotlin-type-mapper",
  "sourceRoot": "/absolute/path/to/src/main/kotlin",
  "typeHierarchy": { "kotlin.collections.List": ["kotlin.collections.Collection", "kotlin.Any"] },
  "files": [
    {
      "relativePath": "Example.kt",
      "packageFqName": "com.example",
      "declarations": [
        {
          "kind": "function",
          "name": "greet",
          "fqName": "com.example.greet",
          "returnType": "kotlin.String",
          "annotations": [],
          "line": 5, "column": 1
        }
      ],
      "calls": [
        {
          "callee": "kotlin.String#trim()",
          "dispatchReceiver": "kotlin.String",
          "returnType": "kotlin.String",
          "line": 8, "column": 12
        }
      ]
    }
  ]
}
```

## Architecture

| File | Module | Role |
|---|---|---|
| `AstModel.kt` | `:model` | Data model (`TypedAst`, `FileAst`, `DeclarationAst`, `CallSiteAst`, …) |
| `TypedAstJson.kt` | `:model` | JSON serialisation / deserialisation |
| `TypedAstQuery.kt` | `:model` | Query extensions (`callsMatching`, `implementorsOf`, …) |
| `SignatureMatcher.kt` | `:model` | Pattern-based call-site matching |
| `KotlinAnalyzer.kt` | `:analyzer` | Compiler setup, semantic analysis, entry point |
| `DeclarationExtractor.kt` | `:analyzer` | PSI visitor for declarations and annotations |
| `CallSiteExtractor.kt` | `:analyzer` | PSI visitor for call sites and property reads |
| `ReflectionTypeHierarchy.kt` | `:analyzer` | Reflection-based type hierarchy builder |
| `ClasspathResolver.kt` | `:analyzer` | Resolves Gradle/Maven runtime classpath |
| `TypeMapper.kt` | `:cli` | Clikt 5 CLI (`analyze`, `load`, `query`) |

## Tests

```bash
./gradlew test
```

## License

Apache 2.0 — see [LICENSE](LICENSE).
