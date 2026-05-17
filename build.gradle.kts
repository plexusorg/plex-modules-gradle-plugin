plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.3.21"
}

group = "dev.plex"
version = "1.0-SNAPSHOT"

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

publishing {
    repositories {
        maven {
            name = "plex"
            val releasesRepoUrl = uri("https://nexus.telesphoreo.me/repository/gradle-plugins-releases/")
            val snapshotsRepoUrl = uri("https://nexus.telesphoreo.me/repository/gradle-plugins-snapshots/")

            url = if (rootProject.version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials(PasswordCredentials::class)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
