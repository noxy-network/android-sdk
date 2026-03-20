plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
    id("maven-publish")
    id("signing")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

android {
    namespace = "network.noxy.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.bouncycastle:bcutil-jdk18on:1.83")

    implementation("io.grpc:grpc-okhttp:1.60.1")
    implementation("io.grpc:grpc-protobuf-lite:1.60.1")
    implementation("io.grpc:grpc-stub:1.60.1")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.google.protobuf:protobuf-javalite:3.25.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

val sdkVersion: String = project.findProperty("NOXY_SDK_VERSION")?.toString() ?: "1.0.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "network.noxy"
                artifactId = "android-sdk"
                version = sdkVersion

                from(components["release"])

                pom {
                    name.set("Noxy Android SDK")
                    description.set("Decentralized push notification SDK for Web3 apps. Wallet-based identity, end-to-end encrypted notifications.")
                    url.set("https://github.com/noxy-network/android-sdk")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            name.set("Noxy Network")
                            organization.set("Noxy Network")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/noxy-network/android-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/noxy-network/android-sdk.git")
                        url.set("https://github.com/noxy-network/android-sdk")
                    }
                }
            }
        }
        // Upload to Maven Central is handled by Nmcp (Central Publisher Portal), not maven-publish repositories.
    }

    signing {
        val signingKey: String? = findProperty("signingKey") as String?
        val signingPassword: String? = findProperty("signingPassword") as String?
    
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}
