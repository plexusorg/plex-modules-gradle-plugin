plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.4.21"
}

group = "dev.plex"
version = "1.3.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("plexModule") {
            id = "dev.plex.module"
            implementationClass = "dev.plex.gradle.PlexModulePlugin"
            displayName = "Plex Module Gradle Plugin"
            description = "Adds Plex module runtime library metadata to module jars."
        }
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
