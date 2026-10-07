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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BirthBySleepFinalReMixPspIsoPatcher"
include(":composeApp")

if (settingsDir.resolve("third_party/oneui-compose/settings.gradle.kts").isFile) {
    includeBuild("third_party/oneui-compose") {
        dependencySubstitution {
            substitute(module("com.github.Ragnarok93.oneui-compose:lib"))
                .using(project(":lib"))
        }
    }
}
