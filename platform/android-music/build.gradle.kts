plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.theacar.android.music"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "CABIN_QUICK_CHECK", "false")
    }
    buildTypes {
        getByName("debug") {
            val quickCheck = providers.gradleProperty("cabinQuickCheck").map { it.toBoolean() }.getOrElse(false)
            buildConfigField("boolean", "CABIN_QUICK_CHECK", quickCheck.toString())
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":feature:music"))
    implementation(project(":core:sensing"))
    implementation(project(":data:spotify"))
    implementation(project(":data:bgm"))
    implementation(project(":data:weather"))
    implementation(project(":platform:android-location"))
    implementation(project(":core:ui"))
    implementation(project(":platform:android-security"))
    implementation(libs.spotify.app.remote)
    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
