import java.time.Year

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
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

    // Compile targeting Java 17 bytecode so the artifact runs on Java 17+
    // (use compiler flags rather than toolchain to avoid requiring a JDK 17 installation)
    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin> {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
