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
        // sherpa-onnx publishes the Android AAR (native keyword spotter) here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "KAI"
include(":app")
