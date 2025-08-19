import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

group = "io.slopshell.gitmonitor"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    
    // Web server
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-gson:2.3.5")
    implementation("io.ktor:ktor-server-cors:2.3.5")
    implementation("io.ktor:ktor-server-websockets:2.3.5")
    implementation("io.ktor:ktor-server-html-builder:2.3.5")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // File watching
    implementation("io.methvin:directory-watcher:0.18.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")
    
    // Command line arguments
    implementation("com.github.ajalt.clikt:clikt:4.2.1")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("io.slopshell.gitmonitor.GitMonitorServerKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.slopshell.gitmonitor.GitMonitorServerKt"
    }
    // Create fat JAR
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Task to run the server
tasks.register<JavaExec>("runServer") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.slopshell.gitmonitor.GitMonitorServerKt")
    
    // Pass through command line arguments
    args = listOf("--port", "8080", "--repo", ".")
    
    // Enable hot reload for development
    jvmArgs = listOf("-Xmx512m")
}