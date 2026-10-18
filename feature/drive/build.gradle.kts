plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:environment"))
            api(project(":feature:route"))
            api(project(":feature:navigation"))
            api(project(":feature:music"))
            api(project(":feature:cabin"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.theacar.feature.drive"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}
