import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val navigationProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.theacar.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theacar.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        listOf("VALHALLA_URL", "MAP_STYLE_URL", "GEOCODING_URL").forEach { name ->
            val defaultValue = if (name == "MAP_STYLE_URL") "https://tiles.openfreemap.org/styles/liberty" else ""
            manifestPlaceholders[name] = providers.gradleProperty(name).orElse(navigationProperties.getProperty(name, defaultValue)).get()
        }
        manifestPlaceholders["SPOTIFY_CLIENT_ID"] = providers.gradleProperty("SPOTIFY_CLIENT_ID")
            .orElse(navigationProperties.getProperty("SPOTIFY_CLIENT_ID", "")).get().trim()
        listOf("ROUTING_TOKEN", "MEMORY_WEB_URL", "MEMORY_WEB_TOKEN").forEach { name ->
            manifestPlaceholders[name] = providers.gradleProperty(name)
                .orElse(navigationProperties.getProperty(name, "")).get().trim()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:sensing"))
    implementation(project(":platform:android-auto"))
    implementation(project(":platform:android-navigation"))
    implementation(project(":platform:android-music"))
    implementation(project(":platform:android-window"))
    implementation(project(":platform:android-memory"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    coreLibraryDesugaring(libs.android.desugar)
}
