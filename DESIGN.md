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
