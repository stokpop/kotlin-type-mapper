plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
    signing
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

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kotlin-type-mapper-model"
            from(components["java"])
            pom {
                name.set("kotlin-type-mapper-model")
                description.set("Model classes for Kotlin Type Mapper: type-name mapping and call-site data structures for PMD Kotlin rule analysis.")
                url.set("https://github.com/stokpop/kotlin-type-mapper")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
                    connection.set("scm:git:https://github.com/stokpop/kotlin-type-mapper.git")
                    developerConnection.set("scm:git:git@github.com:stokpop/kotlin-type-mapper.git")
                    url.set("https://github.com/stokpop/kotlin-type-mapper")
                }
            }
        }
    }
}

val hasSigningKey = project.hasProperty("signingKeyId") || project.hasProperty("signingKey")
if (hasSigningKey) {
    signing {
        isRequired = gradle.taskGraph.hasTask("publish")
        val signingKeyId = findProperty("signingKeyId") as String?
        val signingKey = findProperty("signingKey") as String?
        val signingPassword = findProperty("signingPassword") as String?
        if (signingKeyId != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications)
    }
}
