plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "com.projectx"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // The Project X script API, published as release assets. Drop the jars in libs/.
    compileOnly(fileTree("libs") { include("*.jar") })

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// The engine loads script jars from here on startup.
tasks.register<Copy>("installScripts") {
    dependsOn("jar")
    from(layout.buildDirectory.dir("libs")) { include("*.jar") }
    into(providers.systemProperty("user.home").map { "$it/.projectx/scripts" })
}
