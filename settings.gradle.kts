pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "habit-tracker"
include(":shared", ":backend")
if (providers.gradleProperty("backendOnly").orNull != "true") {
    include(":mcp-probe", ":android-app")
}
