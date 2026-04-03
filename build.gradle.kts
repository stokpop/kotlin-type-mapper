import java.time.Year

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("com.github.hierynomus.license") version "0.16.1" apply false
}

allprojects {
    group = property("group").toString()
    version = property("version").toString()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "com.github.hierynomus.license")

    configure<nl.javadude.gradle.plugins.license.LicenseExtension> {
        header = rootProject.file("HEADER")
        ext.set("year", Year.now().value)
        ext.set("name", "Peter Paul Bakker, Stokpop Software Solutions")
        skipExistingHeaders = true
    }

    // Ensure consistent Java toolchain and Kotlin JVM target
    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(21)
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}
