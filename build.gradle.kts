plugins {
    java
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.projectx"
version = "1.0.0"

repositories {
    mavenCentral()
    // The script API is published as GitHub release assets, not to Maven Central.
    exclusiveContent {
        forRepository {
            ivy {
                name = "Project X script API"
                url = uri("https://github.com/iEasyScript/script-api/releases/download")
                patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("com.projectx") }
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // compileOnly: the engine already has these classes loaded, so they must not be bundled.
    val projectxApi = providers.gradleProperty("projectxApiVersion").get()
    compileOnly("com.projectx:projectx-engine-api:$projectxApi")
    compileOnly("com.projectx:projectx-core:$projectxApi")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

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
