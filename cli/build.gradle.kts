import java.net.URI
import org.gradle.api.file.RelativePath

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

tasks.register("downloadMemoryCheck") {
    description = "Downloads the memory-check test project source if it does not exist yet."
    val targetDir = layout.buildDirectory.dir("memory-check").get().asFile
    val archiveFile = layout.buildDirectory.file("tmp/memory-check-main.tar.gz").get().asFile
    onlyIf { !targetDir.resolve("src/main/kotlin").exists() }
    doLast {
        targetDir.mkdirs()
        archiveFile.parentFile.mkdirs()

        URI.create("https://github.com/stokpop/memory-check/archive/refs/heads/master.tar.gz")
            .toURL()
            .openStream().use { input ->
                archiveFile.outputStream().use { output -> input.copyTo(output) }
            }

        copy {
            from(tarTree(resources.gzip(archiveFile)))
            into(targetDir)
            includeEmptyDirs = false
            eachFile {
                val segments = relativePath.segments
                if (segments.size <= 1) {
                    exclude()
                } else {
                    relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
                }
            }
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn("downloadMemoryCheck")
    // Default: analyze memory-check and write to typemapper-output.json
    val memoryCheck = file("${layout.buildDirectory.get()}/memory-check/src/main/kotlin")
    args = listOf("analyze", "--output", "typemapper-output.json", memoryCheck.absolutePath)
}
