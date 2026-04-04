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
package nl.stokpop.typemapper.analyzer

import java.io.File
import java.net.URLClassLoader
import nl.stokpop.typemapper.model.javaToKotlinName
import nl.stokpop.typemapper.model.kotlinToJavaName

/** Converts a Java binary class name (from reflection) to the Kotlin FQN used in the AST. */
internal fun javaNameToKotlinFqn(javaName: String): String =
    javaToKotlinName(javaName).let { if (it == javaName) javaName.replace('$', '.') else it }

/** Resolves the Java binary class name for a (raw, generic-free) Kotlin FQN. */
internal fun kotlinFqnToJavaName(kotlinFqn: String): String =
    kotlinToJavaName(kotlinFqn)

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

/** Creates a [URLClassLoader] over the given classpath entries (jars or directories), with the current JVM as parent. */
fun buildClassLoader(classpath: List<File>): URLClassLoader =
    URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
