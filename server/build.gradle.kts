// MIJI backend (Kotlin / JVM)
// Runs on the teacher's laptop. Talks to:
//   - Android devices over local Wi-Fi via TCP sockets
//   - Python AI service on http://localhost:8000 via HTTP

plugins {
    kotlin("jvm") version "1.9.24"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // HTTP client for talking to the Python AI service (localhost:8000)
    // implementation("io.ktor:ktor-client-core:2.3.12")
    // implementation("io.ktor:ktor-client-cio:2.3.12")
    // implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    // implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // JSON serialization (shared message format with the Android app)
    // implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.miji.server.MainKt")
}

kotlin {
    jvmToolchain(17)
}
