pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.gradleup.nmcp.settings") version "1.4.4"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "noxy-android-sdk"
include(":noxy-sdk")

// Maven Central via Sonatype Central Publisher Portal (not OSSRH staging API).
// Uses https://central.sonatype.com/ — default publishingType is AUTOMATIC (publish after validation).
nmcpSettings {
    centralPortal {
        // Use System.getenv (not environmentVariable providers) so configuration cache can isolate lifecycle callbacks.
        username.set(System.getenv("SONATYPE_USERNAME") ?: "")
        password.set(System.getenv("SONATYPE_PASSWORD") ?: "")
    }
}
