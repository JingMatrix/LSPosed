import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.lsplugin.cmaker)
    alias(libs.plugins.lsplugin.jgit)
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.nav.safeargs) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ktfmt)
}

/** A ValueSource that executes 'git rev-list --count' to get the total commit count. */
abstract class GitCommitCountValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result =
            execOperations.exec {
                commandLine("git", "rev-list", "--count", "refs/remotes/origin/master")
                standardOutput = output
                isIgnoreExitValue = true
            }
        // Return the count if successful, otherwise a default of "1".
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().trim()
        } else {
            "1"
        }
    }
}

/** A ValueSource that executes 'git tag' to get the latest version tag. */
abstract class GitLatestTagValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        val result =
            execOperations.exec {
                commandLine("git", "tag", "--list", "--sort=-v:refname")
                standardOutput = output
                isIgnoreExitValue = true
            }
        // If successful, parse the first line. Provide a default if no tags are found.
        return if (result.exitValue == 0 && output.toString().isNotBlank()) {
            output.toString().lineSequence().first().removePrefix("v")
        } else {
            "1.0"
        }
    }
}

// This defers the execution of the git commands and allows Gradle to cache the results.
val versionCodeProvider by extra(providers.of(GitCommitCountValueSource::class.java) {})
val versionNameProvider by extra(providers.of(GitLatestTagValueSource::class.java) {})


val repo = jgit.repo()
val commitCount = (repo?.commitCount("refs/remotes/origin/master") ?: 1) + 4200
val latestTag = repo?.latestTag?.removePrefix("v") ?: "1.0"

val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)

val defaultManagerPackageName by extra("org.lsposed.manager")
val verCode by extra(commitCount)
val verName by extra(latestTag)


cmaker {
    default {
        arguments.addAll(
            arrayOf(
                "-DVECTOR_ROOT=${rootDir.absolutePath}",
                "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
            )
        )
        val flags = arrayOf(
            "-DINJECTED_UID=$injectedPackageUid",
            "-DVERSION_CODE=${verCode}",
            "-DVERSION_NAME='\"${verName}\"'",
            "-Wno-gnu-string-literal-operator-template",
            "-Wno-c++2b-extensions",
        )
        cFlags.addAll(flags)
        cppFlags.addAll(flags)
        abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
    buildTypes {
        if (it.name == "release") {
            arguments += "-DDEBUG_SYMBOLS_PATH=${
                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
            }"
        }
    }
}


val androidTargetSdkVersion by extra(36)
val androidMinSdkVersion by extra(27)
val androidBuildToolsVersion by extra("36.0.0")
val androidCompileSdkVersion by extra(36)
val androidCompileNdkVersion by extra("29.0.13113456")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

tasks.register("Delete", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            externalNativeBuild {
                cmake {
                    version = "3.29.8+"
                    buildStagingDirectory = layout.buildDirectory.get().asFile
                }
            }

            defaultConfig {
                minSdk = androidMinSdkVersion
                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion
                    versionCode = verCode
                    versionName = verName
                }
            }

            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }

            compileOptions {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include("*.gradle.kts", "*/build.gradle.kts")
    dependsOn(":xposed:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }
