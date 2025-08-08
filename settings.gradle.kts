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
//        mavenCentral()
//        maven { url = uri("https://webrtc.github.io/webrtc-maven") } // ✅ Add this
//        maven { url = uri("https://jitpack.io") }
        maven { url= uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "Team-Talk-Kotlin"
include(":app")
