import java.time.Year

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "9.4.1"
    id("com.github.jk1.dependency-license-report") version "3.1.2"
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
    dependsOn("generateLicenseReport")
    from(layout.buildDirectory.dir("reports/dependency-license")) { into("META-INF/licenses") }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

tasks.jar {
    archiveBaseName.set("kotlin-type-mapper-cli")
}

tasks.register("cloneMemoryCheck") {
    description = "Downloads the memory-check test project source if it does not exist yet."
    val targetDir = file("${layout.buildDirectory.get()}/memory-check")
    onlyIf { !targetDir.exists() }
    doLast {
        targetDir.mkdirs()
        // Download source archive from GitHub — no .git folder created.
        val process = ProcessBuilder(
            "bash", "-c",
            "curl -fsSL https://github.com/stokpop/memory-check/archive/refs/heads/main.tar.gz" +
            " | tar -xz --strip-components=1 -C ${targetDir.absolutePath}"
        )
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("Download of memory-check failed with exit code $exitCode")
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("cloneMemoryCheck")
    // Default: analyze memory-check and write to typemapper-output.json
    val memoryCheck = file("${layout.buildDirectory.get()}/memory-check/src/main/kotlin")
    args = listOf("analyze", "--output", "typemapper-output.json", memoryCheck.absolutePath)
}
