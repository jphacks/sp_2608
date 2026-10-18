plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.theacar.auto"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
    // Action のクリック登録が android.os.Binder を呼ぶため、単体テストでは既定値を返させる
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":platform:android-navigation"))
    implementation(project(":feature:cabin"))
    implementation(libs.androidx.car.app)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.core)
    coreLibraryDesugaring(libs.android.desugar)
    testImplementation(kotlin("test-junit"))
}
