plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

android {
    namespace = "org.matrix.vector.xposed"

    buildFeatures { androidResources { enable = false } }
}

dependencies {
    api(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
}
