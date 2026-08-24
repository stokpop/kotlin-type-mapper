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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** All possible kinds of a [DeclarationAst]. */
@Serializable
enum class DeclarationKind {
    @SerialName("function")              FUNCTION,
    @SerialName("property")              PROPERTY,
    @SerialName("class")                 CLASS,
    @SerialName("data_class")            DATA_CLASS,
    @SerialName("sealed_class")          SEALED_CLASS,
    @SerialName("enum")                  ENUM,
    @SerialName("enum_entry")            ENUM_ENTRY,
    @SerialName("interface")             INTERFACE,
    @SerialName("annotation")            ANNOTATION,
    @SerialName("object")                OBJECT,
    @SerialName("companion_object")      COMPANION_OBJECT,
    @SerialName("data_object")           DATA_OBJECT,
    @SerialName("value_class")           VALUE_CLASS,
    @SerialName("constructor")           CONSTRUCTOR,
    @SerialName("typealias")             TYPEALIAS,
    @SerialName("for_loop_variable")     FOR_LOOP_VARIABLE,
    @SerialName("catch_variable")        CATCH_VARIABLE,
    @SerialName("lambda_parameter")      LAMBDA_PARAMETER,
    @SerialName("destructured_variable") DESTRUCTURED_VARIABLE,
}

internal val CLASS_KINDS = setOf(
    DeclarationKind.CLASS, DeclarationKind.DATA_CLASS, DeclarationKind.SEALED_CLASS,
    DeclarationKind.VALUE_CLASS, DeclarationKind.ENUM, DeclarationKind.INTERFACE,
    DeclarationKind.ANNOTATION, DeclarationKind.OBJECT, DeclarationKind.COMPANION_OBJECT,
    DeclarationKind.DATA_OBJECT,
)

@Serializable
data class AnnotationAst(
    val fqName: String,
    val arguments: List<String> = emptyList(),
)

/** Variance of a type argument (Kotlin projection / Java wildcard). */
@Serializable
enum class TypeVariance {
    /** No variance annotation — plain `T`. */
    @SerialName("invariant") INVARIANT,
    /** `out T` — covariant (Java `? extends T`). */
    @SerialName("out")       OUT,
    /** `in T` — contravariant (Java `? super T`). */
    @SerialName("in")        IN,
    /** `*` — star projection (Java `?`). */
    @SerialName("star")      STAR,
}

/**
 * A single type argument in a generic type, e.g. the `String` in `List<String>`
 * or the `*` in `List<*>`.
 */
@Serializable
data class TypeArgumentAst(
    val variance: TypeVariance = TypeVariance.INVARIANT,
    /** The projected type.  Null only for [TypeVariance.STAR] projections. */
    val type: TypeAst? = null,
)

/**
 * Structured representation of a Kotlin type, carrying both a fully-qualified name and
 * metadata such as nullability, generic arguments, and resolution status.
 *
 * When a type cannot be resolved (e.g. missing dependency jar), [isUnresolved] is `true`
 * and [fqName] / [simpleName] contain the best-effort name extracted from the source code
 * and file imports.
 */
@Serializable
data class TypeAst(
    /** Fully-qualified name when resolved; best-effort FQN from imports when unresolved;
     *  simple name as last resort. */
    val fqName: String,
    /** Short class name as it appears in source (e.g. `"List"`, `"HttpClient"`). */
    val simpleName: String,
    /** True when the type is marked nullable (`?`) in source. */
    val isNullable: Boolean = false,
    /** True when the compiler could not resolve this type (missing classpath dependency). */
    val isUnresolved: Boolean = false,
    /** Generic type arguments, in declaration order. Empty for non-generic types. */
    val typeArguments: List<TypeArgumentAst> = emptyList(),
) {
    /**
     * Renders this type as a fully-qualified string, matching the legacy `String` representation.
     * Includes generic arguments and nullable marker.
     * Example: `"kotlin.collections.List<kotlin.String>?"`.
     */
    fun toFqString(): String = buildString {
        append(fqName)
        if (typeArguments.isNotEmpty()) {
            append('<')
            typeArguments.joinTo(this, ", ") { arg ->
                when (arg.variance) {
                    TypeVariance.STAR      -> "*"
                    TypeVariance.INVARIANT -> arg.type?.toFqString() ?: "?"
                    TypeVariance.OUT       -> "out ${arg.type?.toFqString() ?: "?"}"
                    TypeVariance.IN        -> "in ${arg.type?.toFqString() ?: "?"}"
                }
            }
            append('>')
        }
        if (isNullable) append('?')
    }

    override fun toString(): String = toFqString()
}

@Serializable
data class ParameterAst(
    val name: String,
    val type: TypeAst,
)

@Serializable
data class CallSiteAst(
    val calleeFqName: String,
    val dispatchReceiverType: TypeAst? = null,    // non-null for regular method calls
    val extensionReceiverType: TypeAst? = null,   // non-null for extension function calls
    val returnType: TypeAst,
    val argumentTypes: List<TypeAst> = emptyList(),
    /** 1-based line of the start of the call expression node.
     *  For method calls (`foo.bar()`) this is the line of the callee name `bar`, not the receiver `foo`.
     *  For property reads (`foo.size`) this is the line of the property name `size`. */
    val line: Int,
    val column: Int,
    /** 1-based line of the end of the call expression node.
     *  For function calls this is the closing `)`. For property reads it is the end of the property name.
     *  Equal to [line] for single-line expressions. Defaults to 0 for ASTs loaded from
     *  older JSON (schema < 1.5) that did not record this field. */
    val endLine: Int = 0,
    val endColumn: Int = 0,
)

@Serializable
data class DeclarationAst(
    val kind: DeclarationKind,
    val name: String,
    val fqName: String,
    val containingDeclaration: String,
    val returnType: TypeAst? = null,            // function only
    val type: TypeAst? = null,                  // property / variable only
    val parameters: List<ParameterAst> = emptyList(),
    val annotations: List<AnnotationAst> = emptyList(),
    /** Direct supertypes (Java canonical FQNs, e.g. java.lang.Exception) for class-kind declarations,
     *  extracted from K1 source analysis. Empty for non-class kinds.
     *  Populated regardless of whether compiled classes are available. */
    val superTypes: List<String> = emptyList(),
    /** Source-text of super-type references as written in the file (e.g. ["HttpClient"] when the
     *  jar is absent and K1 cannot resolve the FQN). Populated only for class-kind declarations.
     *  Used together with file imports for lenient type-resolution mode. */
    val textualSuperTypes: List<String> = emptyList(),
    /** 1-based line of the first token of the declaration, skipping any leading KDoc comment.
     *  Points to the first modifier or keyword (e.g. `fun`, `class`, `val`, `@Annotation`).
     *  For annotated declarations this is the annotation line, not the keyword line. */
    val line: Int,
    val column: Int,
    /** 1-based line of the last token of the declaration (closing `}` for block declarations,
     *  end of expression for single-expression properties). Equal to [line] for one-liners.
     *  Defaults to 0 for ASTs loaded from older JSON (schema < 1.5). */
    val endLine: Int = 0,
    val endColumn: Int = 0,
    /** For TYPEALIAS declarations: ordered list starting with this alias FQN, followed by each
     *  intermediate alias FQN, and ending with the concrete (non-alias) expanded type string.
     *  The last element is the analyzer's rendered type and may include generic arguments
     *  (e.g. `"kotlin.collections.List<kotlin.String>"` for `typealias A = List<String>`).
     *  Example: `["com.example.A", "com.example.B", "kotlin.String"]` for `typealias A = B`
     *  where `typealias B = String`. Empty for all other declaration kinds. */
    val typeAliasChain: List<String> = emptyList(),
) {
    /** Returns true if this declaration represents a class-like type (class, interface, object, etc.). */
    fun isClassLike(): Boolean = kind in CLASS_KINDS
}

@Serializable
data class UnresolvedReferenceAst(
    val name: String,
    val line: Int,
    val column: Int,
)

@Serializable
data class FileAst(
    val relativePath: String,
    val packageFqName: String,
    val declarations: List<DeclarationAst>,
    val calls: List<CallSiteAst> = emptyList(),
    val unresolvedReferences: List<UnresolvedReferenceAst> = emptyList(),
    val contentHash: String = "",              // SHA-256 of source file content
    /** Explicit import FQNs from the source file (star-imports excluded). */
    val imports: List<String> = emptyList(),
)

/** Controls how type names are resolved when a dependency jar is absent from the classpath. */
enum class TypeResolutionMode {
    /** Require a full hierarchy entry; return no match for unresolved simple names. Default. */
    STRICT,
    /** Resolve simple names via file imports for exact-match; emit a warning when fallback fires. */
    LENIENT_WARN,
    /** Resolve simple names via file imports for exact-match; suppress the warning. */
    LENIENT_QUIET,
}

@Serializable
data class TypedAst(
    val schemaVersion: String = "2.0",
    val generatedBy: String = "kotlin-type-mapper",
    /** Absolute path of the common source-root directory used during analysis.
     *  **Never null.** Empty string (`""`) when analysis was performed in-memory
     *  (e.g. via `KotlinTypeMapper.fromSources`); a non-empty path otherwise.
     *  Use [hasSourceRoot] to distinguish the two cases, and
     *  [resolveAbsolutePath] to safely construct absolute file paths. */
    val sourceRoot: String,
    val files: List<FileAst>,
    /** Direct supertypes per type FQN, built via reflection at analysis time.
     *  Key: raw type FQN (no generics). Value: list of direct supertype FQNs.
     *  Kotlin-mapped names are used (e.g. kotlin.Any, kotlin.collections.List). */
    val typeHierarchy: Map<String, List<String>> = emptyMap(),
) {
    /**
     * Returns true when this AST was produced from files on disk (i.e. [sourceRoot] is
     * non-empty). Returns false for in-memory analyses created via
     * `KotlinTypeMapper.fromSources`.
     */
    fun hasSourceRoot(): Boolean = sourceRoot.isNotEmpty()

    /**
     * Resolves the absolute path of [file] by joining [sourceRoot] with [FileAst.relativePath].
     * Returns `null` when [sourceRoot] is empty, which happens for in-memory analyses where
     * no files exist on disk.
     *
     * Prefer this helper over manual string concatenation: when [sourceRoot] is empty,
     * naive concatenation produces a root-relative path (e.g. `/Foo.kt`) instead of a
     * proper absolute path, which is almost certainly wrong.
     */
    fun resolveAbsolutePath(file: FileAst): String? {
        if (sourceRoot.isEmpty()) {
            return null
        }
        return sourceRoot.trimEnd('/', '\\') + java.io.File.separator + file.relativePath
    }
}
