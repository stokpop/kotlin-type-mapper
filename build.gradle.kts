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
}
