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
import java.io.File
import java.net.URLClassLoader

/**
 * Maps well-known Java class names to their Kotlin FQN equivalents.
 * Supertypes obtained via reflection are converted through this table so that
 * the [TypedAst.typeHierarchy] uses the same Kotlin FQNs as the rest of the AST.
 */
private val JAVA_TO_KOTLIN = mapOf(
    "java.lang.Object"       to "kotlin.Any",
    "java.lang.String"       to "kotlin.String",
    "java.lang.Number"       to "kotlin.Number",
    "java.lang.Comparable"   to "kotlin.Comparable",
    "java.lang.CharSequence" to "kotlin.CharSequence",
    "java.lang.Cloneable"    to "kotlin.Cloneable",
    "java.lang.Enum"         to "kotlin.Enum",
    "java.lang.Annotation"   to "kotlin.Annotation",
    "java.lang.Throwable"    to "kotlin.Throwable",
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
 * Maps Kotlin FQNs to the Java binary class name used for [Class.forName] lookups.
 * Mutable variants resolve to the same JVM class as their read-only counterparts.
 */
private val KOTLIN_TO_JAVA = mapOf(
    "kotlin.Any"                          to "java.lang.Object",
    "kotlin.String"                       to "java.lang.String",
    "kotlin.Number"                       to "java.lang.Number",
    "kotlin.Comparable"                   to "java.lang.Comparable",
    "kotlin.CharSequence"                 to "java.lang.CharSequence",
    "kotlin.Cloneable"                    to "java.lang.Cloneable",
    "kotlin.Enum"                         to "java.lang.Enum",
    "kotlin.Annotation"                   to "java.lang.Annotation",
    "kotlin.Throwable"                    to "java.lang.Throwable",
    "kotlin.io.Serializable"              to "java.io.Serializable",
    "kotlin.collections.Iterable"         to "java.lang.Iterable",
    "kotlin.collections.MutableIterable"  to "java.lang.Iterable",
    "kotlin.collections.Iterator"         to "java.util.Iterator",
    "kotlin.collections.MutableIterator"  to "java.util.Iterator",
    "kotlin.collections.Collection"       to "java.util.Collection",
    "kotlin.collections.MutableCollection" to "java.util.Collection",
    "kotlin.collections.List"             to "java.util.List",
    "kotlin.collections.MutableList"      to "java.util.List",
    "kotlin.collections.Set"              to "java.util.Set",
    "kotlin.collections.MutableSet"       to "java.util.Set",
    "kotlin.collections.Map"              to "java.util.Map",
    "kotlin.collections.MutableMap"       to "java.util.Map",
    "kotlin.collections.Map.Entry"        to "java.util.Map\$Entry",
    "kotlin.collections.MutableMap.MutableEntry" to "java.util.Map\$Entry",
)

/** Converts a Java binary class name (from reflection) to the Kotlin FQN used in the AST. */
internal fun javaNameToKotlinFqn(javaName: String): String =
    JAVA_TO_KOTLIN[javaName] ?: javaName.replace('$', '.')

/** Resolves the Java binary class name for a (raw, generic-free) Kotlin FQN. */
internal fun kotlinFqnToJavaName(kotlinFqn: String): String =
    KOTLIN_TO_JAVA[kotlinFqn] ?: kotlinFqn

/** Attempts to load the class for [kotlinFqn] (raw, no generics) from [classLoader]. */
internal fun loadClass(kotlinFqn: String, classLoader: ClassLoader): Class<*>? {
    val javaName = kotlinFqnToJavaName(kotlinFqn)
    return try {
        classLoader.loadClass(javaName)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * Builds a type hierarchy map by reflecting on [seedTypes] and their transitive supertypes.
 *
 * Starting from [seedTypes] (Kotlin FQNs, generics stripped), each class is loaded via
 * [classLoader], its superclass and interfaces are recorded, and those are queued for
 * processing too. The result maps each reachable type FQN to its list of direct supertypes.
 *
 * Types that cannot be loaded (e.g. missing deps) are silently skipped.
 * `kotlin.Any` is included as a supertype but itself has no entry (it is the root).
 */
fun buildTypeHierarchy(seedTypes: Set<String>, classLoader: ClassLoader): Map<String, List<String>> {
    val result = mutableMapOf<String, MutableList<String>>()
    val queue = ArrayDeque(seedTypes.map { it.substringBefore('<') }.distinct())
    val visited = mutableSetOf<String>()

    while (queue.isNotEmpty()) {
        val kotlinFqn = queue.removeFirst()
        if (!visited.add(kotlinFqn)) continue

        val clazz = loadClass(kotlinFqn, classLoader) ?: continue
        val supertypes = mutableListOf<String>()

        clazz.superclass?.let { sc ->
            val name = javaNameToKotlinFqn(sc.name)
            supertypes.add(name)
            if (name !in visited) queue.add(name)
        }
        clazz.interfaces.forEach { iface ->
            val name = javaNameToKotlinFqn(iface.name)
            supertypes.add(name)
            if (name !in visited) queue.add(name)
        }

        if (supertypes.isNotEmpty()) result[kotlinFqn] = supertypes
    }

    return result
}

/** Creates a [URLClassLoader] over the given classpath jars, with the current JVM as parent. */
fun buildClassLoader(classpathJars: List<File>): URLClassLoader =
    URLClassLoader(classpathJars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
