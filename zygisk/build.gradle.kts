import org.apache.commons.codec.binary.Hex
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.lsplugin.resopt)
}

val moduleName = "Vector"
val moduleBaseId = "lsposed"
val authors = "JingMatrix"

val injectedPackageName: String by rootProject.extra
val injectedPackageUid: Int by rootProject.extra

val defaultManagerPackageName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra

android {

    namespace = "org.lsposed.lspd"

    buildFeatures { buildConfig = true }

    defaultConfig {
        multiDexEnabled = false

        buildConfigField(
            "String",
            "DEFAULT_MANAGER_PACKAGE_NAME",
            """"$defaultManagerPackageName""""
        )
        buildConfigField("String", "MANAGER_INJECTED_PKG_NAME", """"$injectedPackageName"""")
        buildConfigField("int", "MANAGER_INJECTED_UID", """$injectedPackageUid""")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

}
abstract class Injected @Inject constructor(val magiskDir: String) {
    @get:Inject
    abstract val factory: ObjectFactory
}

dependencies {
    implementation(projects.core)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.managerService)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.hiddenapi.stubs)
}

val zipAll = tasks.register("zipAll") {
    group = "Vector"
}

fun afterEval() = android.applicationVariants.forEach { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    val variantLowered = variant.name.lowercase()
    val buildTypeCapped = variant.buildType.name.replaceFirstChar { it.uppercase() }
    val buildTypeLowered = variant.buildType.name.lowercase()

    val magiskDir = layout.buildDirectory.dir("magisk/$variantLowered")

    val moduleId = "zygisk_$moduleBaseId"
    val zipFileName = "$moduleName-v$verName-$verCode-$buildTypeLowered.zip"

    val prepareMagiskFilesTask = tasks.register<Sync>("prepareMagiskFiles$variantCapped") {
        group = "Vector"
        dependsOn(
            "assemble$variantCapped",
            ":app:package$buildTypeCapped",
            ":daemon:package$buildTypeCapped",
            ":dex2oat:externalNativeBuild${buildTypeCapped}"
        )
        into(magiskDir)
        from("${rootProject.projectDir}/README.md")
        from("$projectDir/magisk_module") {
            exclude("module.prop", "customize.sh", "daemon")
        }
        from("$projectDir/magisk_module") {
            include("module.prop")
            expand(
                "moduleId" to moduleId,
                "versionName" to "v$verName",
                "versionCode" to verCode,
                "authorList" to authors,
                "updateJson" to "https://raw.githubusercontent.com/JingMatrix/LSPosed/master/magisk-loader/update/zygisk.json",
                "requirement" to "Requires Magisk 26.0+ and Zygisk enabled",
            )
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from("$projectDir/magisk_module") {
            include("customize.sh", "daemon")
            val tokens = mapOf(
                "DEBUG" to if (buildTypeLowered == "debug") "true" else "false"
            )
            filter<ReplaceTokens>("tokens" to tokens)
            filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
        }
        from(project(":app").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "manager.apk")
        }
        from(project(":daemon").tasks.getByName("package$buildTypeCapped").outputs) {
            include("*.apk")
            rename(".*\\.apk", "daemon.apk")
        }
        into("lib") {
            val libDir = variantLowered + "/strip${variantCapped}DebugSymbols"
            from(layout.buildDirectory.dir("intermediates/stripped_native_libs/$libDir/out/lib")) {
                include("**/libzygisk.so")
            }
        }
        into("bin") {
            from(project(":dex2oat").layout.buildDirectory.dir("intermediates/cmake/$buildTypeLowered/obj")) {
                include("**/dex2oat")
                include("**/liboat_hook.so")
            }
        }
        val dexOutPath = if (buildTypeLowered == "release")
            layout.buildDirectory.dir("intermediates/dex/$variantLowered/minify${variantCapped}WithR8")
        else
            layout.buildDirectory.dir("intermediates/dex/$variantLowered/mergeDex$variantCapped")
        into("framework") {
            from(dexOutPath)
            rename("classes.dex", "lspd.dex")
        }
        val injected = objects.newInstance<Injected>(magiskDir.get().asFile.path)
        doLast {
            injected.factory.fileTree().from(injected.magiskDir).visit {
                if (isDirectory) return@visit
                val md = MessageDigest.getInstance("SHA-256")
                file.forEachBlock(4096) { bytes, size ->
                    md.update(bytes, 0, size)
                }
                File(file.path + ".sha256").writeText(Hex.encodeHexString(md.digest()))
            }
        }
    }

    val zipTask = tasks.register<Zip>("zip${variantCapped}") {
        group = "Vector"
        dependsOn(prepareMagiskFilesTask)
        archiveFileName = zipFileName
        destinationDirectory = file("$projectDir/release")
        from(magiskDir)
    }

    zipAll.configure {
        dependsOn(zipTask)
    }

    val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
    val pushTask = tasks.register<Exec>("push${variantCapped}") {
        group = "Vector"
        dependsOn(zipTask)
        workingDir("${projectDir}/release")
        commandLine(adb, "push", zipFileName, "/data/local/tmp/")
    }
    val installMagiskTask = tasks.register<Exec>("installMagisk${variantCapped}") {
        group = "Vector"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "magisk --install-module /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("installMagiskAndReboot${variantCapped}") {
        group = "Vector"
        dependsOn(installMagiskTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
    val installKsuTask = tasks.register<Exec>("installKsu${variantCapped}") {
        group = "Vector"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "ksud module install /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("installKsuAndReboot${variantCapped}") {
        group = "Vector"
        dependsOn(installKsuTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
    val installAPatchTask = tasks.register<Exec>("installAPatch${variantCapped}") {
        group = "Vector"
        dependsOn(pushTask)
        commandLine(
            adb, "shell", "su", "-c",
            "apd module install /data/local/tmp/${zipFileName}"
        )
    }
    tasks.register<Exec>("installAPatchAndReboot${variantCapped}") {
        group = "Vector"
        dependsOn(installAPatchTask)
        commandLine(adb, "shell", "su", "-c", "/system/bin/svc", "power", "reboot")
    }
}

afterEvaluate {
    afterEval()
}

evaluationDependsOn(":app")
evaluationDependsOn(":daemon")
