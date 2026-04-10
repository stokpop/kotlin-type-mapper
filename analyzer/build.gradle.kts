plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":model"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
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
