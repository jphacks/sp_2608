plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

val allowedModuleDependencies = mapOf(
    ":core:model" to emptySet(),
    ":core:location" to setOf(":core:model"),
    ":feature:navigation" to setOf(":feature:route", ":core:location"),
    ":platform:android-navigation" to setOf(":feature:navigation", ":core:ui", ":data:valhalla", ":platform:android-security", ":platform:android-location", ":platform:android-memory"),
    ":platform:android-location" to setOf(":core:location"),
    ":platform:android-security" to emptySet(),
    ":platform:android-window" to setOf(":feature:cabin", ":core:ui"),
    ":platform:android-music" to setOf(":feature:music", ":core:sensing", ":data:spotify", ":data:bgm", ":core:ui", ":platform:android-security", ":platform:android-location", ":data:weather"),
    ":data:weather" to setOf(":core:environment"),
    ":data:spotify" to setOf(":feature:music"),
    ":data:bgm" to setOf(":feature:music"),
    ":data:valhalla" to setOf(":feature:route"),
    ":core:environment" to setOf(":core:model"),
    ":core:ui" to emptySet(),
    ":core:sensing" to emptySet(),
    ":core:inference" to emptySet(),
    ":feature:perception" to setOf(":core:sensing", ":core:inference"),
    ":data:perception" to setOf(":core:environment", ":feature:perception"),
    ":feature:route" to setOf(":core:model"),
    ":feature:music" to setOf(":core:model", ":core:environment", ":core:sensing"),
    ":feature:cabin" to setOf(":core:model"),
    ":feature:drive" to setOf(":core:environment", ":feature:route", ":feature:music", ":feature:cabin", ":feature:navigation"),
    ":feature:dashboard" to setOf(":feature:drive", ":core:ui"),
    ":data:demo" to setOf(":core:environment", ":feature:route", ":feature:music", ":feature:perception"),
    // 車内の知らせを車載画面に出すため :feature:cabin を見る。契約だけで、BLE の実装には触らない。
    ":platform:android-auto" to setOf(":platform:android-navigation", ":feature:cabin"),
    ":shared" to setOf(":feature:drive", ":feature:dashboard", ":data:demo", ":data:perception"),
    ":feature:memory" to setOf(":feature:navigation"),
    ":data:memory" to setOf(":feature:memory"),
    ":platform:android-memory" to setOf(":feature:memory", ":data:memory", ":core:ui"),
    ":androidApp" to setOf(":shared", ":core:sensing", ":platform:android-auto", ":platform:android-navigation", ":platform:android-music", ":platform:android-window", ":platform:android-memory"),
)

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Checks project dependencies against the module ownership rules."
    doLast {
        val violations = mutableListOf<String>()
        subprojects.filter { it.buildFile.exists() }.forEach { module ->
            val allowed = allowedModuleDependencies[module.path]
                ?: error("Declare dependency rules for ${module.path}")
            module.configurations.flatMap { it.dependencies }
                .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                .map { it.path }.distinct()
                .filter { it != module.path && it !in allowed }
                .forEach { violations += "${module.path} -> $it" }
        }
        check(violations.isEmpty()) {
            "Forbidden module dependencies:\n" + violations.joinToString("\n")
        }
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
        dependsOn(verifyModuleBoundaries)
    }
}
