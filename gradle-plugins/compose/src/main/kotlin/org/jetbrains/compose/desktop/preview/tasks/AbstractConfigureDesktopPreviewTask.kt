package org.jetbrains.compose.desktop.preview.tasks

import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger as GradleLogger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.jetbrains.compose.ComposeBuildConfig
import org.jetbrains.compose.desktop.application.internal.currentTarget
import org.jetbrains.compose.desktop.application.internal.javaExecutable
import org.jetbrains.compose.desktop.application.internal.notNullProperty
import org.jetbrains.compose.desktop.tasks.AbstractComposeDesktopTask
import org.jetbrains.compose.desktop.ui.tooling.preview.rpc.*
import java.io.File

abstract class AbstractConfigureDesktopPreviewTask : AbstractComposeDesktopTask() {
    @get:InputFiles
    internal lateinit var previewClasspath: FileCollection

    @get:Internal
    internal val javaHome: Property<String> = objects.notNullProperty<String>().apply {
        set(providers.systemProperty("java.home"))
    }

    // todo
    @get:Input
    @get:Optional
    internal val jvmArgs: ListProperty<String> = objects.listProperty(String::class.java)

    @get:Input
    internal val previewTarget: Provider<String> =
        project.providers.gradleProperty("compose.desktop.preview.target")

    @get:Input
    internal val idePort: Provider<String>  =
        project.providers.gradleProperty("compose.desktop.preview.ide.port")

    @get:InputFiles
    internal val uiTooling: FileCollection = project.configurations.detachedConfiguration(
        project.dependencies.create("org.jetbrains.compose.ui:ui-tooling-desktop:${ComposeBuildConfig.composeVersion}")
    ).apply { isTransitive = false }

    @get:InputFiles
    internal val hostClasspath: FileCollection = project.configurations.detachedConfiguration(
        project.dependencies.create("org.jetbrains.compose:preview-rpc:${ComposeBuildConfig.composeVersion}")
    )

    @TaskAction
    fun run() {
        val hostConfig = PreviewHostConfig(
                javaExecutable = javaExecutable(javaHome.get()),
                hostClasspath = hostClasspath.files.asSequence().pathString()
            )
        val previewClasspathString =
            (previewClasspath.files.asSequence() +
                    uiTooling.files.asSequence() +
                    tryGetSkikoRuntimeFilesIfNeeded().asSequence()
            ).pathString()

        val gradleLogger = logger
        val previewLogger = GradlePreviewLoggerAdapter(gradleLogger)

        val connection = getLocalConnectionOrNull(idePort.get().toInt(), previewLogger, onClose = {})
        if (connection != null) {
            connection.use {
                connection.sendConfigFromGradle(
                    hostConfig,
                    previewClasspath = previewClasspathString,
                    previewFqName = previewTarget.get()
                )
            }
        } else {
            gradleLogger.error("Could not connect to IDE")
        }
    }

    private fun tryGetSkikoRuntimeFilesIfNeeded(): Collection<File> {
        try {
            var hasSkikoJvm = false
            var hasSkikoJvmRuntime = false
            var skikoVersion: String? = null
            for (file in previewClasspath.files) {
                if (file.name.endsWith(".jar")) {
                    if (file.name.startsWith("skiko-awt-runtime-")) {
                        hasSkikoJvmRuntime = true
                        continue
                    } else if (file.name.startsWith("skiko-awt-")) {
                        hasSkikoJvm = true
                        skikoVersion = file.name
                            .removePrefix("skiko-awt-")
                            .removeSuffix(".jar")
                    }
                }
            }
            if (hasSkikoJvmRuntime) return emptyList()

            if (hasSkikoJvm && skikoVersion != null && skikoVersion.isNotBlank()) {
                val skikoRuntimeConfig = project.configurations.detachedConfiguration(
                    project.dependencies.create("org.jetbrains.skiko:skiko-awt-runtime-${currentTarget.id}:$skikoVersion")
                ).apply { isTransitive = false }
                return skikoRuntimeConfig.files
            }
        } catch (e: Exception) {
            // OK
        }

        return emptyList()
    }

    private fun Sequence<File>.pathString(): String =
        joinToString(File.pathSeparator) { it.absolutePath }

    private class GradlePreviewLoggerAdapter(
        private val logger: GradleLogger
    ) : PreviewLogger() {
        // todo: support compose.verbose
        override val isEnabled: Boolean
            get() = logger.isDebugEnabled

        override fun log(s: String) {
            logger.info("Compose Preview: $s")
        }
    }
}