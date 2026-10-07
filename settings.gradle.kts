pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PriveeApp"
include(":app")
include(":core:net")
include(":core:signal")

// Release builds for F-Droid and GitHub Releases pass -PlibsignalBuiltFromSource and use
// libsignal built from source (libsignal/README.md) instead of the Maven artifacts.
if (providers.gradleProperty("libsignalBuiltFromSource").isPresent) {
    include(":libsignal:android")
}