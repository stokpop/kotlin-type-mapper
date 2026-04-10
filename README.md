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

The shadow jar is built to `cli/build/libs/kotlin-type-mapper-cli-<version>-all.jar`,
or download it from the [GitHub releases page](https://github.com/stokpop/kotlin-type-mapper/releases).

### Set up `ktm` alias

Add an alias so you can type `ktm` instead of the full `java -jar ...` invocation.

**bash / zsh** — add to `~/.bashrc` or `~/.zshrc`:

```bash
alias ktm='java -jar /path/to/kotlin-type-mapper-cli-<version>-all.jar'
```

**fish** — add to `~/.config/fish/config.fish`:

```fish
alias ktm='java -jar /path/to/kotlin-type-mapper-cli-<version>-all.jar'
```

Replace `/path/to/` with the actual path, e.g.:

```bash
alias ktm='java -jar ~/tools/kotlin-type-mapper-cli-0.3.0-all.jar'
```

After adding, reload your shell (`source ~/.bashrc`) or open a new terminal.

### Analyze a project

```bash
ktm analyze --output result.json /path/to/src/main/kotlin
```

Classpath jars are resolved automatically from the nearest Gradle or Maven build.

### Query from JSON

```bash
ktm query result.json calls "kotlin.String#trim()"
ktm query result.json calls-polymorphic "kotlin.collections.Collection#size()"
ktm query result.json implementors "java.io.Closeable"
ktm query result.json annotated-with "org.springframework.stereotype.Service"
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

// Run analysis (simple: discovers all .kt files under sourceRoot automatically)
val ast: TypedAst = analyzeKotlinProject(
    sourceRoot = File("/path/to/src/main/kotlin"),
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

## Releasing

### Prerequisites

Before your first release, configure these secrets in the GitHub repository settings (`Settings → Secrets and variables → Actions`):

| Secret | Description |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username (generate at [central.sonatype.com](https://central.sonatype.com) → Account → User Token) |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `GPG_SIGNING_KEY` | Full armored PGP private key (`gpg --export-secret-keys --armor <key-id>`) |
| `GPG_SIGNING_PASSWORD` | GPG passphrase |

Also allow the release workflow to push the version back to `main` after publishing. In the branch protection settings for `main`:
- **Classic branch protection**: enable *Allow specified actors to bypass required pull requests* and add `github-actions[bot]`
- **Rulesets** (newer): add `github-actions[bot]` to the bypass list

### Steps to release

1. **Create a GitHub release** via the GitHub GUI:
   - Go to the repo → *Releases* → *Draft a new release*
   - Create a new tag (e.g. `0.4.0`) pointing at `main`
   - Fill in the release notes and click **Publish release**
2. The [Release workflow](.github/workflows/release.yml) triggers automatically:
   - Uses the release tag as the version — no need to update `gradle.properties` first
   - Builds and signs `model`, `analyzer`, and `cli` JARs
   - Bumps `gradle.properties` to the next patch `-SNAPSHOT` version on `main` (e.g. `0.4.0` → `0.4.1-SNAPSHOT`)
   - Uploads the deployment to [Maven Central Portal](https://central.sonatype.com/publishing/deployments)
   - Attaches all JARs to the GitHub release
3. **Approve on Maven Central** — go to [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments) and click **Publish** on the deployment (or **Drop** to abort)

> **Note:** Step 3 is intentionally manual. Maven Central releases are irreversible — always verify the deployment looks correct before publishing.

### Snapshot releases

Trigger the workflow manually via *Actions → Release to Maven Central → Run workflow* and enter a version ending in `-SNAPSHOT` (e.g. `0.4.0-SNAPSHOT`). Snapshots are published automatically without a manual approval step and are available at the [Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/).

## License

Apache 2.0 — see [LICENSE](LICENSE).

