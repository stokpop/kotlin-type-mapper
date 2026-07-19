plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    id("com.vanniktech.maven.publish")
}

val kotlinVersion = "2.4.10"

// Repositories (redirector for -for-ide artifacts + mavenCentral) come from the root
// build's allprojects block. Declaring exclusiveContent for the same redirector URL here
// too makes Gradle 9.6 fail to resolve the -for-ide modules (duplicate exclusive claim).

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":model"))
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")
    implementation("org.jetbrains.kotlin:analysis-api-standalone-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:analysis-api-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:analysis-api-k2-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:analysis-api-impl-base-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:analysis-api-platform-interface-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:low-level-api-fir-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:symbol-light-classes-for-ide:$kotlinVersion") { isTransitive = false }
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core:1.10.2-intellij-1") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3") { isTransitive = false }
    implementation("io.github.java-diff-utils:java-diff-utils:4.16")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = "kotlin-type-mapper-analyzer",
        version = project.version.toString()
    )

    pom {
        name.set("kotlin-type-mapper-analyzer")
        description.set("Kotlin compiler-based call-site and type-hierarchy extractor for PMD Kotlin rule analysis.")
        url.set("https://github.com/stokpop/kotlin-type-mapper")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("stokpop")
                name.set("Peter Paul Bakker")
                url.set("https://github.com/stokpop")
            }
        }
        scm {
            url.set("https://github.com/stokpop/kotlin-type-mapper")
            connection.set("scm:git:git://github.com/stokpop/kotlin-type-mapper.git")
            developerConnection.set("scm:git:ssh://git@github.com/stokpop/kotlin-type-mapper.git")
        }
    }
}
