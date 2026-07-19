# kotlin-type-mapper design notes

## 1. Type hierarchy: seeds and BFS traversal

### What "seeding" means

The type hierarchy cannot be pre-built for the entire JVM - there are thousands of classes.
Instead, it is built lazily from **seed types**: the set of raw type FQNs that appear in the
source code being analysed. The hierarchy builder then follows superclasses and interfaces
transitively from those seeds via reflection, recording each `type -> direct-supertypes` pair
in a map.

A type that is never seeded has **no entry** in the hierarchy map. BFS traversal from that
type finds nothing, so `typeIs('java.util.Collection')` on an unseeded `List` return type
would silently return `false` even though `List` is a subtype of `Collection`.

### What gets seeded (`KotlinAnalyzer.kt`)

Every distinct raw type FQN found in the following positions is added to `seedTypes`:

| Source | Field |
|--------|-------|
| Call site | `dispatchReceiverType` - the object a method is called on |
| Call site | `extensionReceiverType` - the receiver of an extension function call |
| Declaration (class-like) | `fqName` - the declared class/interface/object itself |
| Declaration (function) | `returnType` - the function's return type |
| Declaration (property/variable) | `type` - the declared property type |
| Declaration (parameter) | `parameters[].type` - each parameter type |

"Raw" means: nullable marker (`?`) and generic arguments (`<...>`) are stripped first via
`rawTypeName()` before adding to the seed set.

### BFS traversal (`ReflectionTypeHierarchy.kt -> buildTypeHierarchy`)

Starting from the seed set, each type is loaded via `ClassLoader.loadClass()` using the
Java class name (resolved via `kotlinFqnToJavaName`). The direct superclass and all
implemented interfaces are recorded as direct supertypes for that type (mapped back to Kotlin
FQNs via `javaNameToKotlinFqn`), and then themselves queued for processing. Types that
cannot be loaded (missing deps) are silently skipped.

```
seed: kotlin.collections.List
  -> load java.util.List
  -> supertype: java.util.Collection  -> javaNameToKotlinFqn -> kotlin.collections.Collection
  -> supertype: java.lang.Iterable    -> javaNameToKotlinFqn -> kotlin.collections.Iterable
  -> supertype: java.util.RandomAccess (no Kotlin equivalent -> kept as-is)
  -> queue kotlin.collections.Collection, kotlin.collections.Iterable, ...
```

### Java<->Kotlin name equivalence

Kotlin maps several Java standard-library types to its own aliases (e.g. `java.util.List` <->
`kotlin.collections.List`). Both names refer to the same class at runtime. The
`javaToKotlinName` / `kotlinToJavaName` maps handle these equivalences in:

- `javaNameToKotlinFqn`: converts reflection-produced Java names to Kotlin FQNs when storing
  supertypes in the hierarchy map.
- `kotlinFqnToJavaName`: converts Kotlin FQNs back to Java binary names before loading via
  `ClassLoader`.
- `isTypeEquivalent` (in `pmd-kotlin`): checks whether two FQNs refer to the same type,
  accepting both Java and Kotlin names for the expected type.

### `rawTypeName` helper

Strips trailing `?` (nullable marker) and generic parameters before `<`, giving the raw FQN
used as a map key:

| Input | Output |
|-------|--------|
| `kotlin.collections.List<String>?` | `kotlin.collections.List` |
| `kotlin.collections.List?` | `kotlin.collections.List` |
| `kotlin.collections.List<String>` | `kotlin.collections.List` |
| `kotlin.String` | `kotlin.String` |

Without stripping `?`, `typeHierarchy["kotlin.collections.List?"]` would be `null` and BFS
would find no supertypes for nullable types.

## 4. K2 analysis API

### What is used

The analyzer uses the **K2 Analysis API** (`buildStandaloneAnalysisAPISession`, `KaSession`,
`analyze {}` with symbol APIs like `KaNamedFunctionSymbol`, `KaConstructorSymbol`,
`KaTypeAliasSymbol`). Types are fully resolved — generics, nullability, expanded type aliases.

This is a drop-in replacement for the earlier K1 implementation: the public API and the
`TypedAst` output model are unchanged, so callers need no changes.

### Why K2

The K1 API (`KotlinCoreEnvironment`, `TopDownAnalyzerFacadeForJVM`, `BindingContext`) is marked
`@K1Deprecation` in `kotlin-compiler` 2.x. K2 (`KaSession` / `analyze {}`) is the supported
programmatic analysis surface going forward.

### K2-specific implementation notes

- **Dependencies:** the K2 standalone session needs the `-for-ide` artifacts
  (`analysis-api-standalone-for-ide`, `analysis-api-k2-for-ide`, `low-level-api-fir-for-ide`,
  `symbol-light-classes-for-ide`, ...), all with `isTransitive = false`. They are not on Maven
  Central; they come from the `redirector.kotlinlang.org/maven/intellij-dependencies` repo,
  declared once in the root `allprojects` block (declaring it per-module too breaks resolution).
  Uses the non-embeddable `kotlin-compiler` (not `-embeddable`, which relocates `com.intellij.*`).
- **Constructor calls:** `KaConstructorSymbol` has no `callableId`; calleeFqName is derived as
  `<containingClassId>.<init>` so the `.<init>` convention the model/queries expect is preserved.
- **Type alias chain:** built by walking `KaType.abbreviation` one step at a time
  (`buildAliasChain`) to capture intermediate aliases (e.g. `A -> B -> kotlin.String`).
- **File lists:** `analyzeKotlinFileList` adds each `.kt` file as its own source root
  (`addSourceRoot` accepts file paths, not just directories) so scattered files are analysed
  without scanning the whole filesystem root.
- The rest of the model (`TypedAst`, `TypeHierarchy`, `SignatureMatcher`) is analysis-engine
  independent.
