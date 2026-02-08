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
        // For walt.id SDK (when published)
        maven("https://maven.waltid.dev/releases")
        maven("https://maven.waltid.dev/snapshots")
    }
}

rootProject.name = "verify-kyc-example"
include(":app")

// For local development, include the SDK as a composite build.
// The SDK sources are at ../../waltid-verify-sdk-android
// Note: This only works if the SDK is a properly configured JVM/Android library.
// For now, we include the SDK code directly in this example.
// Uncomment this when the SDK is set up as an Android-compatible library:
// includeBuild("../../waltid-verify-sdk-android") {
//     dependencySubstitution {
//         substitute(module("id.walt:waltid-verify-sdk-android")).using(project(":"))
//     }
// }
