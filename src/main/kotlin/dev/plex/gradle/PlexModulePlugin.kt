package dev.plex.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

class PlexModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(JavaPlugin::class.java)
        val extension = project.extensions.create("plexModule", PlexModuleExtension::class.java)

        val plexLibrary = project.configurations.create(PLEX_LIBRARY_CONFIGURATION) {
            it.description = "Runtime libraries that Plex loads before this module starts."
            it.isCanBeConsumed = false
            it.isCanBeResolved = false
        }

        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            it.extendsFrom(plexLibrary)
        }

        val processResources = project.tasks.named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, ProcessResources::class.java)
        val injectPlexLibraries = project.tasks.register(INJECT_PLEX_LIBRARIES_TASK_NAME, InjectPlexLibrariesTask::class.java) {
            it.group = "plex"
            it.description = "Injects plexLibrary dependencies into module.yml."
            it.dependsOn(processResources)
            it.libraries.set(project.provider {
                plexLibrary.dependencies.map(::toMavenCoordinate)
            })
            it.repositories.set(project.provider {
                if (plexLibrary.dependencies.isEmpty()) emptyMap() else moduleRepositories(project, extension.includedRepositories.get())
            })
            it.moduleYml.fileProvider(processResources.map { processResourcesTask ->
                processResourcesTask.destinationDir.resolve("module.yml")
            })
        }

        project.tasks.named(JavaPlugin.CLASSES_TASK_NAME) {
            it.dependsOn(injectPlexLibraries)
        }
    }

    private fun toMavenCoordinate(dependency: Dependency): String {
        val externalDependency = dependency as? ExternalModuleDependency
            ?: throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: ${dependency.describe()}. " +
                    "Use Maven coordinates like plexLibrary(\"group:name:version\")."
            )

        val group = externalDependency.group.takeIf(String::isNotBlank)
            ?: throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: ${dependency.describe()}. " +
                    "A group is required."
            )
        val name = externalDependency.name.takeIf(String::isNotBlank)
            ?: throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: ${dependency.describe()}. " +
                    "A name is required."
            )
        val version = externalDependency.version?.takeIf(String::isNotBlank)
            ?: throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: $group:$name. " +
                    "An explicit version is required."
            )

        if (!version.isExplicitVersion()) {
            throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: $group:$name:$version. " +
                    "Use a fixed version instead of a dynamic version, version range, or latest.* selector."
            )
        }

        if (externalDependency.artifacts.isNotEmpty()) {
            throw GradleException(
                "Unsupported dependency in $PLEX_LIBRARY_CONFIGURATION: $group:$name:$version. " +
                    "Classifiers and custom artifact declarations are not supported."
            )
        }

        return "$group:$name:$version"
    }

    private fun moduleRepositories(project: Project, includedRepositoryNames: Set<String>): Map<String, String> {
        val repositoriesByUrl = linkedMapOf<String, String>()
        val usedIds = mutableSetOf<String>()
        if (includedRepositoryNames.isEmpty()) {
            return repositoriesByUrl
        }

        project.repositories.withType(MavenArtifactRepository::class.java).forEach { repository ->
            if (repository.name !in includedRepositoryNames) {
                return@forEach
            }
            val url = repository.url.toString().toRuntimeRepositoryUrl() ?: return@forEach
            if (repositoriesByUrl.containsValue(url)) {
                return@forEach
            }

            val baseId = repository.name.toRepositoryId()
            var id = baseId
            var suffix = 2
            while (!usedIds.add(id)) {
                id = "$baseId-$suffix"
                suffix++
            }
            repositoriesByUrl[id] = url
        }

        return repositoriesByUrl
    }

    private fun String.toRuntimeRepositoryUrl(): String? {
        val url = if (MAVEN_CENTRAL_URLS.any { startsWith(it) }) {
            PAPER_MAVEN_CENTRAL_MIRROR
        } else {
            this
        }

        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun String.toRepositoryId(): String {
        val id = lowercase()
            .replace(Regex("[^a-z0-9_.-]"), "-")
            .trim('-', '.', '_')
        return id.ifBlank { "repository" }
    }

    private fun Dependency.describe(): String {
        val groupPart = group ?: "<no group>"
        val versionPart = version ?: "<no version>"
        return "${this::class.java.name}($groupPart:$name:$versionPart)"
    }

    private fun String.isExplicitVersion(): Boolean {
        return !contains("+") &&
            !startsWith("latest.", ignoreCase = true) &&
            !startsWith("[") &&
            !startsWith("(")
    }

    companion object {
        const val PLEX_LIBRARY_CONFIGURATION = "plexLibrary"
        const val INJECT_PLEX_LIBRARIES_TASK_NAME = "injectPlexLibraries"
        private const val PAPER_MAVEN_CENTRAL_MIRROR = "https://maven-central.storage-download.googleapis.com/maven2"
        private val MAVEN_CENTRAL_URLS = listOf(
            "https://repo1.maven.org/maven2",
            "http://repo1.maven.org/maven2",
            "https://repo.maven.apache.org/maven2",
            "http://repo.maven.apache.org/maven2"
        )
    }
}

open class PlexModuleExtension @Inject constructor(objects: ObjectFactory) {
    val includedRepositories: SetProperty<String> = objects.setProperty(String::class.java).convention(emptySet())

    fun includeRepository(name: String) {
        includedRepositories.add(name)
    }

    fun includeRepositories(vararg names: String) {
        includedRepositories.addAll(names.toList())
    }
}

@DisableCachingByDefault(because = "Rewrites module.yml in place; output equals input file, so caching offers no benefit.")
abstract class InjectPlexLibrariesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleYml: RegularFileProperty

    @get:Input
    abstract val libraries: ListProperty<String>

    @get:Input
    abstract val repositories: MapProperty<String, String>

    @TaskAction
    fun inject() {
        val moduleYmlFile = moduleYml.asFile.get()
        if (!moduleYmlFile.isFile) {
            throw GradleException(
                "Plex module metadata file not found at ${moduleYmlFile.path}. " +
                    "Add module.yml to src/main/resources."
            )
        }

        moduleYmlFile.writeText(rewriteModuleYml(moduleYmlFile.readText(), libraries.get(), repositories.get()))
    }

    private fun rewriteModuleYml(content: String, libraries: List<String>, repositories: Map<String, String>): String {
        val lineSeparator = if (content.contains("\r\n")) "\r\n" else "\n"
        val normalizedLines = content.replace("\r\n", "\n").split("\n").dropLastWhile { it.isEmpty() }
        val metadataLines = removeTopLevelBlock(removeTopLevelBlock(normalizedLines, "libraries"), "repositories").toMutableList()

        while (metadataLines.lastOrNull()?.isBlank() == true) {
            metadataLines.removeAt(metadataLines.lastIndex)
        }

        if (repositories.isNotEmpty()) {
            metadataLines += "repositories:"
            metadataLines += repositories.map { (id, url) -> "  $id: $url" }
        }

        if (libraries.isNotEmpty()) {
            metadataLines += "libraries:"
            metadataLines += libraries.map { "  - $it" }
        }

        return metadataLines.joinToString(lineSeparator) + lineSeparator
    }

    private fun removeTopLevelBlock(lines: List<String>, key: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0

        while (index < lines.size) {
            if (lines[index].isTopLevelKey(key)) {
                index++
                while (index < lines.size && (lines[index].isBlank() || lines[index].firstOrNull()?.isWhitespace() == true)) {
                    index++
                }
            } else {
                result += lines[index]
                index++
            }
        }

        return result
    }

    private fun String.isTopLevelKey(key: String): Boolean {
        return startsWith("$key:") || this == key
    }
}
