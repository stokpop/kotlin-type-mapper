import kotlinx.serialization.Serializable

@Serializable
data class ParameterAst(
    val name: String,
    val type: String,
)

@Serializable
data class CallSiteAst(
    val calleeFqName: String,
    val dispatchReceiverType: String? = null,    // non-null for regular method calls
    val extensionReceiverType: String? = null,   // non-null for extension function calls
    val returnType: String,
    val argumentTypes: List<String> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class DeclarationAst(
    val kind: String,                          // "function" | "property" | "class" | ...
    val name: String,
    val fqName: String,
    val containingDeclaration: String,
    val returnType: String? = null,            // function only
    val type: String? = null,                  // property / variable only
    val parameters: List<ParameterAst> = emptyList(),
    val line: Int,
    val column: Int,
)

@Serializable
data class FileAst(
    val relativePath: String,
    val packageFqName: String,
    val declarations: List<DeclarationAst>,
    val calls: List<CallSiteAst> = emptyList(),
)

@Serializable
data class TypedAst(
    val schemaVersion: String = "1.1",
    val generatedBy: String = "kotlin-TypeMapper",
    val sourceRoot: String,
    val files: List<FileAst>,
)
