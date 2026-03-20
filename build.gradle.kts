import org.gradle.api.JavaVersion

// Gradle/AGP/Kotlin 1.9 require Java 17–21. Java 22+ causes build failures.
if (JavaVersion.current() > JavaVersion.VERSION_21) {
    throw GradleException(
        "This project requires Java 21 or lower. Current: ${JavaVersion.current()}. " +
        "Use JAVA_HOME to point to Java 21, e.g.: JAVA_HOME=/path/to/jdk-21 ./gradlew assemble"
    )
}

plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.protobuf") version "0.9.6" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}
