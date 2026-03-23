import com.android.build.api.dsl.ApplicationExtension
import com.android.ide.common.signing.KeystoreHelper
import java.io.PrintStream

val defaultManagerPackageName: String by rootProject.extra
val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra
val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.resopt)
}

android {
    buildFeatures {
        prefab = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.lsposed.daemon"

        buildConfigField(
            "String",
            "DEFAULT_MANAGER_PACKAGE_NAME",
            """"$defaultManagerPackageName"""",
        )
        buildConfigField("String", "FRAMEWORK_NAME", """"${rootProject.name}"""")
        buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
        buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
        buildConfigField("String", "VERSION_NAME", """"${versionNameProvider.get()}"""")
        buildConfigField("long", "VERSION_CODE", versionCodeProvider.get())
    }

    buildTypes {
        all {
            externalNativeBuild { cmake { arguments += "-DANDROID_ALLOW_UNDEFINED_SYMBOLS=true" } }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild { cmake { path("src/main/jni/CMakeLists.txt") } }

    namespace = "org.lsposed.daemon"
}

android.applicationVariants.all {
    val variantCapped = name.replaceFirstChar { it.uppercase() }
    val variantLowered = name.lowercase()

    val outSrcDir = layout.buildDirectory.dir("generated/source/signInfo/${variantLowered}").get()
    val signInfoTask =
        tasks.register("generate${variantCapped}SignInfo") {
            dependsOn(":app:validateSigning${variantCapped}")
            val sign =
                rootProject
                    .project(":app")
                    .extensions
                    .getByType(ApplicationExtension::class.java)
                    .buildTypes
                    .named(variantLowered)
                    .get()
                    .signingConfig
            val outSrc = file("$outSrcDir/org/lsposed/lspd/util/SignInfo.java")
            outputs.file(outSrc)
            doLast {
                outSrc.parentFile.mkdirs()
                val certificateInfo =
                    KeystoreHelper.getCertificateInfo(
                        sign?.storeType,
                        sign?.storeFile,
                        sign?.storePassword,
                        sign?.keyPassword,
                        sign?.keyAlias,
                    )
                PrintStream(outSrc)
                    .print(
                        """
                |package org.lsposed.lspd.util;
                |public final class SignInfo {
                |    public static final byte[] CERTIFICATE = {${
                    certificateInfo.certificate.encoded.joinToString(",")
                }};
                |}"""
                            .trimMargin()
                    )
            }
        }
    registerJavaGeneratingTask(signInfoTask, outSrcDir.asFile)
}

dependencies {
    implementation(libs.agp.apksig)
    implementation(projects.external.apache)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    implementation(projects.services.managerService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}
