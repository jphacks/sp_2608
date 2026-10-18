pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Maven で配布されていない Spotify App Remote SDK だけを取得する。third_party/README.md を参照。
        maven {
            url = uri("third_party/maven")
            content { includeGroup("com.spotify.android") }
        }
    }
}

rootProject.name = "TheaCar"
include(":core:model", ":core:environment", ":core:ui")
include(":core:sensing", ":core:inference", ":feature:perception", ":data:perception")
include(":feature:route", ":feature:music", ":feature:cabin", ":feature:drive", ":feature:dashboard")
include(":data:demo", ":platform:android-auto")
include(":shared")
include(":androidApp")
include(":core:location", ":feature:navigation", ":platform:android-navigation")
include(":data:valhalla")
include(":data:spotify", ":platform:android-music", ":platform:android-security")
include(":data:weather", ":platform:android-location")
include(":data:bgm")
include(":platform:android-window")
include(":feature:memory", ":platform:android-memory")
include(":data:memory")
