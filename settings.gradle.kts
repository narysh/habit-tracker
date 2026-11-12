pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "habit-tracker"
include(":mcp-probe")
include(":shared", ":backend", ":android-app")
