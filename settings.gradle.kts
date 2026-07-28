pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MPAndroidChart is only published to JitPack. Scoped to that one group rather than opened
        // globally, so a typo in any other coordinate fails instead of silently resolving a
        // same-named artifact built from someone's fork.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.PhilJay") }
        }
    }
}

rootProject.name = "StressGuard"
include(":app")
include(":wear")
