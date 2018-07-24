package net.ltgt.gradle.errorprone.javacplugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.FeatureExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.TestedExtension
import com.android.build.gradle.api.BaseVariant
import org.gradle.api.DomainObjectSet
import org.gradle.api.Named
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.* // ktlint-disable no-wildcard-imports
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.util.GradleVersion

class ErrorProneJavacPluginPlugin : Plugin<Project> {

    companion object {
        const val PLUGIN_ID = "net.ltgt.errorprone-javacplugin"

        const val CONFIGURATION_NAME = "errorprone"

        const val DEFAULT_DEPENDENCY = "com.google.errorprone:error_prone_core:latest.release"

        private val SUPPORTS_LAZY_TASKS = GradleVersion.current() >= GradleVersion.version("4.9")
    }

    override fun apply(project: Project) {
        if (GradleVersion.current() < GradleVersion.version("4.6")) {
            throw UnsupportedOperationException("$PLUGIN_ID requires at least Gradle 4.6")
        }

        val errorproneConfiguration = project.configurations.create(CONFIGURATION_NAME) {
            isVisible = false
            exclude(group = "com.google.errorprone", module = "javac")
            defaultDependencies { add(project.dependencies.create(DEFAULT_DEPENDENCY)) }
        }

        project.plugins.withType<JavaBasePlugin> {
            val java = project.convention.getPlugin<JavaPluginConvention>()
            java.sourceSets.all {
                project.configurations[annotationProcessorConfigurationName].extendsFrom(errorproneConfiguration)
                project.configureTask<JavaCompile>(compileJavaTaskName) {
                    ErrorProneJavacPlugin.apply(it.options)
                }
            }
        }

        project.plugins.withType<JavaPlugin> {
            project.configureTask<JavaCompile>(JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME) {
                it.options.errorprone.isCompilingTestOnlyCode = true
            }
        }

        arrayOf("application", "library", "feature", "test", "instantapp").forEach {
            project.plugins.withId("com.android.$it") {
                val configure: BaseVariant.() -> Unit = {
                    annotationProcessorConfiguration.extendsFrom(errorproneConfiguration)
                    (javaCompiler as? JavaCompile)?.options?.also(ErrorProneJavacPlugin::apply)
                }

                val android = project.extensions.getByName<BaseExtension>("android")
                (android as? AppExtension)?.applicationVariants?.configureVariant(configure)
                (android as? LibraryExtension)?.libraryVariants?.configureVariant(configure)
                (android as? FeatureExtension)?.featureVariants?.configureVariant(configure)
                (android as? TestExtension)?.applicationVariants?.configureVariant(configure)
                if (android is TestedExtension) {
                    android.testVariants.configureVariant(configure)
                    android.unitTestVariants.configureVariant(configure)
                }
            }
        }
    }

    private inline fun <reified T : Task> Project.configureTask(taskName: String, noinline action: (T) -> Unit) {
        if (SUPPORTS_LAZY_TASKS) {
            tasks.withType(T::class.java).named(taskName).configure(action)
        } else {
            tasks.withType(T::class.java).getByName(taskName, action)
        }
    }

    private fun DomainObjectSet<out BaseVariant>.configureVariant(action: BaseVariant.() -> Unit) {
        if (SUPPORTS_LAZY_TASKS) {
            this.configureEach(action)
        } else {
            this.all(action)
        }
    }
}

object ErrorProneJavacPlugin {
    @JvmStatic
    fun apply(options: CompileOptions) {
        val errorproneOptions =
            (options as ExtensionAware).extensions.create(ErrorProneOptions.NAME, ErrorProneOptions::class.java)
        options
            .compilerArgumentProviders
            .add(ErrorProneCompilerArgumentProvider(errorproneOptions))
    }
}

internal class ErrorProneCompilerArgumentProvider(private val errorproneOptions: ErrorProneOptions) :
    CommandLineArgumentProvider, Named {

    override fun getName(): String = "errorprone"

    @Suppress("unused")
    @Nested
    @Optional
    fun getErrorproneOptions(): ErrorProneOptions? {
        return errorproneOptions.takeIf { it.isEnabled }
    }

    override fun asArguments(): Iterable<String> {
        return when {
            errorproneOptions.isEnabled -> listOf("-Xplugin:ErrorProne $errorproneOptions", "-XDcompilePolicy=simple")
            else -> emptyList()
        }
    }
}
