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
            implementation(project(":core:environment"))
            implementation(project(":feature:route"))
            implementation(project(":feature:music"))
            implementation(project(":feature:perception"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.theacar.data.demo"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}
