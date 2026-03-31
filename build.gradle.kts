plugins {
    kotlin("jvm") version "2.2.21"
}

group = "nl.stokpop.typemapper"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
}

tasks.test {
    useJUnitPlatform()
}
