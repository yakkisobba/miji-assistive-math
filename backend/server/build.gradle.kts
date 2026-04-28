// MIJI backend (Kotlin / JVM)
// Runs on the teacher's laptop. Talks to:
//   - Android devices over local Wi-Fi via TCP sockets
//   - Python AI service on http://localhost:8000 via HTTP

plugins {
    kotlin("jvm")
    application
}

// Note: repositories are declared centrally in settings.gradle.kts under
// dependencyResolutionManagement. Per FAIL_ON_PROJECT_REPOS, modules must NOT
// declare their own repositories. google() and mavenCentral() are inherited.

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
    // Match the JDK that the :app module compiles against (Java 11)
    jvmToolchain(11)
}
