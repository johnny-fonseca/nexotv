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
    }
}

rootProject.name = "NexoIPTV"
include(":app")

val problemsReportFile = file("build/reports/problems/problems-report.html")
if (problemsReportFile.exists()) {
    try {
        problemsReportFile.delete()
    } catch (_: Exception) {
        // Ignore if locked or unable to delete
    }
}
