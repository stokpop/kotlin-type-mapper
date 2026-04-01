import java.time.Year

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("com.github.hierynomus.license") version "0.16.1"
}

group = "nl.stokpop.typemapper"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:1.9.10")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.10")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("TypeMapperKt")
}

license {
    header = rootProject.file("HEADER")
    ext.set("year", Year.now().value)
    ext.set("name", "Peter Paul Bakker, Stokpop Software Solutions")
    skipExistingHeaders = true
}

tasks.register("cloneMemoryCheck") {
    description = "Clones the memory-check test project if it does not exist yet."
    val targetDir = file("test-projects/memory-check")
    onlyIf { !targetDir.exists() }
    doLast {
        targetDir.parentFile.mkdirs()
        val process = ProcessBuilder("git", "clone", "https://github.com/stokpop/memory-check.git", targetDir.absolutePath)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("git clone failed with exit code $exitCode")
    }
}

tasks.named("run") {
    dependsOn("cloneMemoryCheck")
}