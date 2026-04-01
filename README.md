# Kotlin TypeMapper

Scans Kotlin source files in a Gradle or Maven project and extracts a semantic AST — all declarations (functions, classes, properties, etc.) and resolved call sites with full type information — serialised to JSON.

Analysis is performed using the Kotlin K1 compiler (embedded) so types are fully resolved, including generics and nullability.

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run --args="[sourceDir] [outputFile]"
```

| Argument | Default |
|---|---|
| `sourceDir` | `test-projects/memory-check/src/main/kotlin` |
| `outputFile` | `typemapper-output.json` |

If `test-projects/memory-check/` does not exist it is cloned automatically from [github.com/stokpop/memory-check](https://github.com/stokpop/memory-check).

Example with explicit paths:

```bash
./gradlew run --args="/path/to/kotlin/src result.json"
```

## Output format

```json
{
  "schemaVersion": "1.1",
  "generatedBy": "kotlin-type-mapper",
  "sourceRoot": "/absolute/path/to/source",
  "files": [
    {
      "relativePath": "Example.kt",
      "packageFqName": "com.example",
      "declarations": [ ... ],
      "calls": [ ... ]
    }
  ]
}
```

Each `declarations` entry carries the declaration kind, fully-qualified name, type, and source position.  
Each `calls` entry carries the callee FQN, dispatch/extension receiver types, return type, argument types, and source position.

## Signature matching

`SignatureMatcher` lets you query call sites with PMD-style patterns:

```
receiverType#methodName(paramType, ...)
```

Wildcards: `_` matches any single type or name; `*` matches any parameter list.

```kotlin
callSite.matchesSig("kotlin.String#trim()")   // exact
callSite.matchesSig("_#trim()")               // any receiver
callSite.matchesSig("kotlin.String#_(*)")     // any method, any params
```

## Tests

```bash
./gradlew test
```

## Architecture

| File | Role |
|---|---|
| `TypeMapper.kt` | Entry point — CLI args, file discovery, classpath resolution, JSON output |
| `KotlinAnalyzer.kt` | Compiler setup and semantic analysis |
| `DeclarationExtractor.kt` | PSI visitor for declarations |
| `CallSiteExtractor.kt` | PSI visitor for call sites |
| `SignatureMatcher.kt` | Pattern-based call-site matching |
| `TypeRenderer.kt` | Converts compiler types to FQN strings |
| `AstModel.kt` | Serialisable data model (`TypedAst`, `FileAst`, …) |
| `ClasspathResolver.kt` | Resolves Gradle/Maven runtime classpath |

## License

Apache 2.0 — see [LICENSE](LICENSE).
