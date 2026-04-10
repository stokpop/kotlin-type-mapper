plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("stdlib"))
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
        artifactId = "kotlin-type-mapper-model",
        version = project.version.toString()
    )

    pom {
        name.set("kotlin-type-mapper-model")
        description.set("Model classes for Kotlin Type Mapper: type-name mapping and call-site data structures for PMD Kotlin rule analysis.")
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
