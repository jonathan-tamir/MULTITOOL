pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MULTITOOL"
include(":app")
include(":shell")
include(":core")
include(":core-ui")
include(":feature-audio")
include(":feature-image")
include(":feature-video")
include(":feature-drone")

// Screenshot rendering is opt-in: the module (and its Paparazzi plugin) is only configured when
// asked for, so a screenshot-tooling problem can never break the APK build.
if (System.getenv("ENABLE_SCREENSHOTS") == "1") include(":screenshots")
