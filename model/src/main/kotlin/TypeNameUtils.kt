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

/**
 * Maps well-known Java FQNs to their Kotlin FQN equivalents.
 * e.g. `java.lang.String` → `kotlin.String`, `java.util.List` → `kotlin.collections.List`.
 */
internal val JAVA_TO_KOTLIN: Map<String, String> = mapOf(
    "java.lang.Object"       to "kotlin.Any",
    "java.lang.String"       to "kotlin.String",
    "java.lang.Number"       to "kotlin.Number",
    "java.lang.Comparable"   to "kotlin.Comparable",
    "java.lang.CharSequence" to "kotlin.CharSequence",
    "java.lang.Cloneable"    to "kotlin.Cloneable",
    "java.lang.Enum"         to "kotlin.Enum",
    "java.lang.Annotation"   to "kotlin.Annotation",
    // Exception hierarchy — each is a Kotlin typealias for the Java class
    "java.lang.Throwable"                       to "kotlin.Throwable",
    "java.lang.Error"                           to "kotlin.Error",
    "java.lang.Exception"                       to "kotlin.Exception",
    "java.lang.RuntimeException"                to "kotlin.RuntimeException",
    "java.lang.IllegalArgumentException"        to "kotlin.IllegalArgumentException",
    "java.lang.IllegalStateException"           to "kotlin.IllegalStateException",
    "java.lang.IndexOutOfBoundsException"       to "kotlin.IndexOutOfBoundsException",
    "java.lang.ArrayIndexOutOfBoundsException"  to "kotlin.ArrayIndexOutOfBoundsException",
    "java.lang.StringIndexOutOfBoundsException" to "kotlin.StringIndexOutOfBoundsException",
    "java.lang.UnsupportedOperationException"   to "kotlin.UnsupportedOperationException",
    "java.lang.ClassCastException"              to "kotlin.ClassCastException",
    "java.lang.NullPointerException"            to "kotlin.NullPointerException",
    "java.lang.ArithmeticException"             to "kotlin.ArithmeticException",
    "java.lang.NumberFormatException"           to "kotlin.NumberFormatException",
    "java.lang.StackOverflowError"              to "kotlin.StackOverflowError",
    "java.lang.OutOfMemoryError"                to "kotlin.OutOfMemoryError",
    "java.io.Serializable"   to "kotlin.io.Serializable",
    "java.lang.Iterable"     to "kotlin.collections.Iterable",
    "java.util.Iterator"     to "kotlin.collections.Iterator",
    "java.util.Collection"   to "kotlin.collections.Collection",
    "java.util.List"         to "kotlin.collections.List",
    "java.util.Set"          to "kotlin.collections.Set",
    "java.util.Map"          to "kotlin.collections.Map",
    "java.util.Map\$Entry"   to "kotlin.collections.Map.Entry",
)

/**
 * Maps well-known Kotlin FQNs to the Java binary class names used for reflection lookups.
 * Mutable collection variants resolve to the same JVM class as their read-only counterparts.
 */
internal val KOTLIN_TO_JAVA: Map<String, String> = mapOf(
    "kotlin.Any"                               to "java.lang.Object",
    "kotlin.String"                            to "java.lang.String",
    "kotlin.Number"                            to "java.lang.Number",
    "kotlin.Comparable"                        to "java.lang.Comparable",
    "kotlin.CharSequence"                      to "java.lang.CharSequence",
    "kotlin.Cloneable"                         to "java.lang.Cloneable",
    "kotlin.Enum"                              to "java.lang.Enum",
    "kotlin.Annotation"                        to "java.lang.Annotation",
    // Exception hierarchy typealiases
    "kotlin.Throwable"                         to "java.lang.Throwable",
    "kotlin.Error"                             to "java.lang.Error",
    "kotlin.Exception"                         to "java.lang.Exception",
    "kotlin.RuntimeException"                  to "java.lang.RuntimeException",
    "kotlin.IllegalArgumentException"          to "java.lang.IllegalArgumentException",
    "kotlin.IllegalStateException"             to "java.lang.IllegalStateException",
    "kotlin.IndexOutOfBoundsException"         to "java.lang.IndexOutOfBoundsException",
    "kotlin.ArrayIndexOutOfBoundsException"    to "java.lang.ArrayIndexOutOfBoundsException",
    "kotlin.StringIndexOutOfBoundsException"   to "java.lang.StringIndexOutOfBoundsException",
    "kotlin.UnsupportedOperationException"     to "java.lang.UnsupportedOperationException",
    "kotlin.ClassCastException"                to "java.lang.ClassCastException",
    "kotlin.NullPointerException"              to "java.lang.NullPointerException",
    "kotlin.ArithmeticException"               to "java.lang.ArithmeticException",
    "kotlin.NumberFormatException"             to "java.lang.NumberFormatException",
    "kotlin.StackOverflowError"                to "java.lang.StackOverflowError",
    "kotlin.OutOfMemoryError"                  to "java.lang.OutOfMemoryError",
    "kotlin.io.Serializable"                   to "java.io.Serializable",
    "kotlin.collections.Iterable"              to "java.lang.Iterable",
    "kotlin.collections.MutableIterable"       to "java.lang.Iterable",
    "kotlin.collections.Iterator"              to "java.util.Iterator",
    "kotlin.collections.MutableIterator"       to "java.util.Iterator",
    "kotlin.collections.Collection"            to "java.util.Collection",
    "kotlin.collections.MutableCollection"     to "java.util.Collection",
    "kotlin.collections.List"                  to "java.util.List",
    "kotlin.collections.MutableList"           to "java.util.List",
    "kotlin.collections.Set"                   to "java.util.Set",
    "kotlin.collections.MutableSet"            to "java.util.Set",
    "kotlin.collections.Map"                   to "java.util.Map",
    "kotlin.collections.MutableMap"            to "java.util.Map",
    "kotlin.collections.Map.Entry"             to "java.util.Map\$Entry",
    "kotlin.collections.MutableMap.MutableEntry" to "java.util.Map\$Entry",
)

/**
 * Converts a Java binary class name to the Kotlin FQN used in the AST.
 * Returns the input unchanged if no mapping exists.
 */
fun javaToKotlinName(javaName: String): String = JAVA_TO_KOTLIN[javaName] ?: javaName

private fun kotlinFqnToJavaBinaryName(kotlinFqn: String): String {
    val segments = kotlinFqn.split('.')
    if (segments.size < 2) return kotlinFqn

    val firstClassIndex = segments.indexOfFirst { segment ->
        segment.isNotEmpty() && segment[0].isUpperCase()
    }
    if (firstClassIndex == -1 || firstClassIndex == segments.lastIndex) return kotlinFqn

    val packageName = segments.take(firstClassIndex).joinToString(".")
    val className = segments.drop(firstClassIndex).joinToString("$")
    return if (packageName.isEmpty()) className else "$packageName.$className"
}

/**
 * Converts a raw (no generics) Kotlin FQN to the Java binary name used for reflection.
 * Returns the input unchanged if no mapping exists.
 */
fun kotlinToJavaName(kotlinFqn: String): String =
    KOTLIN_TO_JAVA[kotlinFqn] ?: kotlinFqnToJavaBinaryName(kotlinFqn)

/**
 * Returns true if two type names refer to the same type after Java↔Kotlin mapping.
 * Both names are first stripped of generics before comparison.
 *
 * Examples:
 * ```
 * typeNamesEquivalent("java.lang.String",        "kotlin.String")           == true
 * typeNamesEquivalent("java.util.List",           "kotlin.collections.List") == true
 * typeNamesEquivalent("java.util.regex.Pattern",  "java.util.regex.Pattern") == true
 * typeNamesEquivalent("java.util.regex.Pattern",  "kotlin.String")           == false
 * ```
 */
fun typeNamesEquivalent(nameA: String, nameB: String): Boolean {
    val rawA = nameA.substringBefore('<')
    val rawB = nameB.substringBefore('<')
    if (rawA == rawB) return true
    return javaToKotlinName(rawA) == rawB
        || rawA == javaToKotlinName(rawB)
        || kotlinToJavaName(rawA) == rawB
        || rawA == kotlinToJavaName(rawB)
}
