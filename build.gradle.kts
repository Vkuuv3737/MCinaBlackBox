plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.mcinablackbox"
version = "2.0-BETA"

repositories {
    mavenCentral()
}

val ktor_version: String by project

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.squareup.okio:okio:3.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.12")

}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.mcinablackbox.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}