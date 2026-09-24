plugins {
    java
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.projectx"
version = "1.0.0"

repositories {
    // com.projectx is excluded from the public repository so it can only ever come from the
    // release repository below - the same guarantee the exclusiveContent block used to give.
    mavenCentral { content { excludeGroup("com.projectx") } }
    // The script API is published as GitHub release assets, not to Maven Central. The ivy
    // descriptor is what lets the IDE find the -sources jar next to the jar: with artifact-only
    // metadata Gradle has nothing to read and never looks for sources. Releases published before
    // the descriptors existed have none, so artifact() keeps those resolving exactly as before.
    ivy {
        name = "Project X script API"
        url = uri("https://github.com/iEasyScript/script-api/releases/download")
        patternLayout {
            ivy("v[revision]/ivy-[module]-[revision].xml")
            artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]")
        }
        metadataSources {
            ivyDescriptor()
            artifact()
        }
        content { includeGroup("com.projectx") }
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
