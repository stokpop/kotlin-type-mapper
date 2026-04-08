import java.time.Year

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "9.4.1"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":analyzer"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("nl.stokpop.typemapper.cli.TypeMapperKt")
}

tasks.shadowJar {
    archiveBaseName.set("kotlin-type-mapper-cli")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.jar {
    archiveBaseName.set("kotlin-type-mapper-cli")
}

tasks.register("cloneMemoryCheck") {
    description = "Clones the memory-check test project if it does not exist yet."
    val targetDir = file("${rootProject.projectDir}/test-projects/memory-check")
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

tasks.named<JavaExec>("run") {
    dependsOn("cloneMemoryCheck")
    // Default: analyze memory-check and write to typemapper-output.json
    val memoryCheck = rootProject.file("test-projects/memory-check/src/main/kotlin")
    args = listOf("analyze", "--output", "typemapper-output.json", memoryCheck.absolutePath)
}
