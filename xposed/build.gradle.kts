plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

android {
    namespace = "org.matrix.vector.xposed"

    buildFeatures { androidResources { enable = false } }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin", "libxposed/api/src/main/java")
        }
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
