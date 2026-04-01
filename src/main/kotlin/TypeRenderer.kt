import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

/** Renders a [KotlinType] as a fully-qualified string, including generic arguments. */
fun KotlinType.toFqString(): String {
    val descriptor = constructor.declarationDescriptor
        ?: return toString()                        // error / unresolved type
    val fqn = descriptor.fqNameSafe.asString()
    val nullable = if (isMarkedNullable) "?" else ""
    if (arguments.isEmpty()) return "$fqn$nullable"
    val args = arguments.joinToString(", ") {
        if (it.isStarProjection) "*" else it.type.toFqString()
    }
    return "$fqn<$args>$nullable"
}
