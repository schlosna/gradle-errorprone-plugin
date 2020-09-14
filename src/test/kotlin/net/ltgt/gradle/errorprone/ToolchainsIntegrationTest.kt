package net.ltgt.gradle.errorprone

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.Before
import org.junit.Test

class ToolchainsIntegrationTest : AbstractPluginIntegrationTest() {

    companion object {
        private val FORKED = "${System.lineSeparator()}Fork: true${System.lineSeparator()}"
        private val NOT_FORKED = "${System.lineSeparator()}Fork: false${System.lineSeparator()}"
        private val JVM_ARG = "${System.lineSeparator()}JVM Arg: "
        private val JVM_ARG_BOOTCLASSPATH = jvmArg("-Xbootclasspath/p:")
        private val JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC = Regex.escape(File.separator).let { fileSeparator ->
            val escapedErrorproneJavacVersion = Regex.escape(errorproneJavacVersion)
            """$JVM_ARG_BOOTCLASSPATH.*${fileSeparator}com\.google\.errorprone${fileSeparator}javac$fileSeparator$escapedErrorproneJavacVersion$fileSeparator.*${fileSeparator}javac-$escapedErrorproneJavacVersion.jar[${File.pathSeparator}${System.lineSeparator()}]"""
                .toPattern()
        }

        private fun jvmArg(argPrefix: String) = "$JVM_ARG$argPrefix"
    }

    @Before
    fun setup() {
        assume().that(GradleVersion.version(testGradleVersion).baseVersion).isAtLeast(GradleVersion.version("6.7"))

        testProjectDir.newFile("gradle.properties").appendText(
            """
            org.gradle.java.installations.auto-download=false
            """.trimIndent()
        )

        buildFile.appendText(
            """
            plugins {
                `java-library`
                id("${ErrorPronePlugin.PLUGIN_ID}")
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                errorprone("com.google.errorprone:error_prone_core:$errorproneVersion")
                errorproneJavac("com.google.errorprone:javac:$errorproneJavacVersion")
            }

            tasks {
                val alwaysFail by registering {
                    doFirst {
                        error("Forced failure")
                    }
                }
                val displayCompileJavaOptions by registering {
                    finalizedBy(alwaysFail)
                    doFirst {
                        println("ErrorProne: ${'$'}{if (compileJava.get().options.errorprone.isEnabled.getOrElse(false)) "enabled" else "disabled"}")
                        println("Fork: ${'$'}{compileJava.get().options.isFork}")
                        compileJava.get().options.forkOptions.jvmArgs?.forEach { arg ->
                            println("JVM Arg: ${'$'}arg")
                        }
                    }
                }
                compileJava {
                    finalizedBy(displayCompileJavaOptions)
                }
            }
            """.trimIndent()
        )
        writeSuccessSource()
    }

    private fun BuildResult.assumeToolchainAvailable() {
        if (task("alwaysFail") == null &&
            (
                output.contains("No compatible toolchains found for request filter") ||
                    // XXX: specific to 6.7-rc-1 https://github.com/gradle/gradle/issues/14504
                    output.contains("Cannot obtain value from provider of Gradle property 'org.gradle.java.installations.auto-detect' at configuration time.")
                )
        ) {
            assume().withMessage("No compatible toolchains found").fail()
        }
    }

    @Test
    fun `fails when configured toolchain is too old`() {
        // given

        // Fake a JDK 7 toolchain, the task should never actually run anyway.
        // We cannot even use a real toolchain and rely on auto-download=false as that
        // would fail too early (when computing task inputs, so our doFirst won't run)
        buildFile.appendText(
            """

            tasks.compileJava {
                javaCompiler.set(object : JavaCompiler {
                    override fun getExecutablePath(): RegularFile = TODO()
                    override fun getMetadata(): JavaInstallationMetadata = object : JavaInstallationMetadata {
                        override fun getLanguageVersion(): JavaLanguageVersion = JavaLanguageVersion.of(7)
                        override fun getInstallationPath(): Directory = TODO()
                    }
                })
            }
            """.trimIndent()
        )

        // First test that it's disabled by default

        // when
        buildWithArgsAndFail("displayCompileJavaOptions").also { result ->
            // then
            assertThat(result.task(":displayCompileJavaOptions")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains("ErrorProne: disabled")
        }

        // Then test that it fails if we force-enable it

        buildFile.appendText(
            """

            tasks.compileJava { options.errorprone.isEnabled.set(true) }
            """.trimIndent()
        )

        // when
        buildWithArgsAndFail("compileJava").also { result ->
            // then
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
            assertThat(result.output).contains(ErrorPronePlugin.TOO_OLD_TOOLCHAIN_ERROR_MESSAGE)
        }
    }

    @Test
    fun `does not configure forking in non-Java 8 VM`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(6))
                }
            }
            """.trimIndent()
        )

        // when
        buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(NOT_FORKED)
            assertThat(result.output).doesNotContain(JVM_ARG)
        }

        // Test a forked task

        // given
        buildFile.appendText(
            """

            tasks.compileJava { options.isFork = true }
            """.trimIndent()
        )

        // when
        buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(FORKED)
            assertThat(result.output).doesNotContain(JVM_ARG)
        }
    }

    @Test
    fun `configure forking in Java 8 VM`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }
            """.trimIndent()
        )

        // when
        buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
            assertThat(result.output).contains(FORKED)
            assertThat(result.output).containsMatch(JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC)
        }

        // check that it doesn't mess with task avoidance

        // when
        buildWithArgsAndFail("compileJava").also { result ->
            // then
            result.assumeToolchainAvailable()
            assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `does not configure forking if Error Prone is disabled`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }

            tasks.compileJava { options.errorprone.isEnabled.set(false) }
            """.trimIndent()
        )

        // when
        val result = buildWithArgsAndFail("compileJava")

        // then
        result.assumeToolchainAvailable()
        assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains(NOT_FORKED)
        assertThat(result.output).doesNotContain(JVM_ARG)
    }

    @Test
    fun `configure bootclasspath for already-forked tasks`() {
        // given
        buildFile.appendText(
            """

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(8))
                }
            }

            tasks.compileJava {
                options.isFork = true
                options.forkOptions.jvmArgs!!.add("-XshowSettings")
            }
            """.trimIndent()
        )

        // when
        val result = buildWithArgsAndFail("compileJava")

        // then
        result.assumeToolchainAvailable()
        assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.output).contains(FORKED)
        assertThat(result.output).containsMatch(JVM_ARG_BOOTCLASSPATH_ERRORPRONE_JAVAC)
        // Check that the configured jvm arg is preserved
        assertThat(result.output).contains(jvmArg("-XshowSettings"))
    }
}
