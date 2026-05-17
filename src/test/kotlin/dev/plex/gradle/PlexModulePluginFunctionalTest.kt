package dev.plex.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlexModulePluginFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `writes only plexLibrary dependencies into module metadata`() {
        createMavenArtifact("dev.plex.test", "runtime-lib", "1.0")
        createMavenArtifact("dev.plex.test", "implementation-only", "1.0")
        writeModuleProject(
            """
            plexLibrary("dev.plex.test:runtime-lib:1.0")
            implementation("dev.plex.test:implementation-only:1.0")
            """.trimIndent()
        )

        val result = gradle("build")

        assertEquals(TaskOutcome.SUCCESS, result.task(":injectPlexLibraries")?.outcome)

        val moduleYml = readModuleYmlFromBuiltJar()
        assertContains(
            moduleYml,
            """
            libraries:
              - dev.plex.test:runtime-lib:1.0
            """.trimIndent()
        )
        assertFalse(
            moduleYml.contains("dev.plex.test:implementation-only:1.0"),
            "implementation dependencies must not be written to Plex module metadata."
        )
    }

    @Test
    fun `adds plexLibrary dependencies to runtimeClasspath`() {
        createMavenArtifact("dev.plex.test", "runtime-lib", "1.0")
        writeModuleProject("""plexLibrary("dev.plex.test:runtime-lib:1.0")""")

        val result = gradle("dependencies", "--configuration", "runtimeClasspath")

        assertContains(result.output, "dev.plex.test:runtime-lib:1.0")
    }

    @Test
    fun `rejects versionless plexLibrary dependencies`() {
        writeModuleProject("""plexLibrary("dev.plex.test:runtime-lib")""")

        val result = gradleAndFail("injectPlexLibraries")

        assertContains(result.output, "An explicit version is required.")
    }

    @Test
    fun `rejects file plexLibrary dependencies`() {
        val localJar = projectDir.resolve("libs/local.jar")
        Files.createDirectories(localJar.parent)
        writeJar(localJar)
        writeModuleProject("""plexLibrary(files("libs/local.jar"))""")

        val result = gradleAndFail("injectPlexLibraries")

        assertContains(result.output, "Use Maven coordinates like plexLibrary(\"group:name:version\").")
    }

    private fun writeModuleProject(dependenciesBlock: String) {
        projectDir.resolve("settings.gradle.kts").writeTextFile(
            """
            rootProject.name = "module-under-test"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeTextFile(
            """
            plugins {
                id("dev.plex.module")
            }

            repositories {
                maven {
                    url = uri("${mavenRepo().toUri()}")
                }
            }

            dependencies {
                $dependenciesBlock
            }
            """.trimIndent()
        )
        projectDir.resolve("src/main/resources/module.yml").writeTextFile(
            """
            name: Module-Test
            version: 1.0
            description: Test module
            main: dev.plex.TestModule
            """.trimIndent()
        )
    }

    private fun createMavenArtifact(group: String, artifact: String, version: String) {
        val artifactDir = mavenRepo()
            .resolve(group.replace('.', '/'))
            .resolve(artifact)
            .resolve(version)
        Files.createDirectories(artifactDir)

        artifactDir.resolve("$artifact-$version.pom").writeTextFile(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>$group</groupId>
                <artifactId>$artifact</artifactId>
                <version>$version</version>
            </project>
            """.trimIndent()
        )
        writeJar(artifactDir.resolve("$artifact-$version.jar"))
    }

    private fun gradle(vararg arguments: String): BuildResult {
        return gradleRunner(*arguments).build()
    }

    private fun gradleAndFail(vararg arguments: String): BuildResult {
        return gradleRunner(*arguments).buildAndFail()
    }

    private fun gradleRunner(vararg arguments: String): GradleRunner {
        return GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(*arguments, "--stacktrace")
            .withPluginClasspath()
    }

    private fun readModuleYmlFromBuiltJar(): String {
        val builtJar = Files.list(projectDir.resolve("build/libs")).use { builtJars ->
            builtJars
                .filter { it.name.endsWith(".jar") }
                .findFirst()
                .orElseThrow { AssertionError("No jar was produced by the test build.") }
        }

        ZipFile(builtJar.toFile()).use { zipFile ->
            val moduleYmlEntry = zipFile.getEntry("module.yml")
                ?: throw AssertionError("module.yml was not written to the built jar.")

            return zipFile.getInputStream(moduleYmlEntry)
                .bufferedReader()
                .use { it.readText() }
        }
    }

    private fun mavenRepo(): Path {
        return projectDir.resolve("repo")
    }

    private fun Path.writeTextFile(content: String) {
        parent?.let { Files.createDirectories(it) }
        Files.writeString(this, content)
    }

    private fun writeJar(path: Path) {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
    }
}
