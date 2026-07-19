/**
 * k2-poc: Isolated Gradle submodule for the K2 Analysis API proof-of-concept.
 *
 * This module uses `kotlin-compiler` (the NON-embeddable artifact) because the
 * K2 `-for-ide` JARs call `KotlinCoreEnvironment` with `com.intellij.openapi.Disposable`
 * (unrelocated). The `kotlin-compiler-embeddable` artifact relocates those classes to
 * `org.jetbrains.kotlin.com.intellij.*`, making it incompatible with K2 on the same
 * JVM classpath. Keeping this module separate avoids any conflict with the K1 production
 * dependencies in `:analyzer`.
 *
 * References:
 *   KT-56203  – open issue to publish analysis-api-standalone to Maven Central
 *   KT-61639  – reason all -for-ide JARs must declare isTransitive=false
 *   KT-73751  – reason Caffeine must be on the classpath
 */

plugins {
    kotlin("jvm")
}

val kotlinVersion = "2.4.10"

// Repositories (redirector for the -for-ide fat JARs + mavenCentral) come from the root
// build's allprojects block. Re-declaring exclusiveContent for the same redirector URL here
// makes Gradle 9.6 fail to resolve the -for-ide modules (duplicate exclusive claim).

dependencies {
    // Non-embeddable compiler: provides `com.intellij.*` classes at their original
    // (non-relocated) package path, which the K2 `-for-ide` JARs require.
    // NOTE: do NOT add kotlin-compiler-embeddable here — it relocates com.intellij.*
    // and would cause a NoSuchMethodError at runtime when K2 tries to call
    // KotlinCoreEnvironment.Companion.getOrCreateApplicationEnvironment(com.intellij.openapi.Disposable, ...).
    testImplementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")

    // K2 Analysis API — all seven `-for-ide` JARs required for standalone analysis.
    // isTransitive=false: their POMs reference non-`-for-ide` counterparts that are
    // not published anywhere (KT-61639).
    testImplementation("org.jetbrains.kotlin:analysis-api-standalone-for-ide:$kotlinVersion") { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:analysis-api-for-ide:$kotlinVersion")             { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:analysis-api-k2-for-ide:$kotlinVersion")          { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:analysis-api-impl-base-for-ide:$kotlinVersion")   { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:analysis-api-platform-interface-for-ide:$kotlinVersion") { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:low-level-api-fir-for-ide:$kotlinVersion")        { isTransitive = false }
    testImplementation("org.jetbrains.kotlin:symbol-light-classes-for-ide:$kotlinVersion")     { isTransitive = false }

    // Caffeine: used internally by the Analysis API for caching (KT-73751).
    testRuntimeOnly("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // IntelliJ-patched coroutines: required by the AA session internals.
    testRuntimeOnly("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1") { isTransitive = false }
    // Required by IntelliJ's XML parsing (XmlElement references KSerializer)
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
