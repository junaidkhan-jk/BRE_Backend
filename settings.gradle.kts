// BRE Platform — root build. Layout per LLD §1.1.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Resolves Java toolchains from the foojay disco API, so the Java 25
    // toolchain declared in bre.java-library is downloaded automatically.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Maven Central only at the root (D11). The Nexus repository needed by
    // trustt-platform-lib is declared solely in the bre.spring-service
    // convention plugin, which no module applies until M2.
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bre-platform"

// libs/ — pure libraries. No Spring anywhere (D3, LLD §1.2).
include(
    ":libs:bre-engine-core",
    ":libs:bre-ir-json",
    ":libs:bre-bundle",
    ":libs:bre-fact-model",
    ":libs:bre-crypto",
    ":libs:bre-contracts",
    ":libs:bre-platform-common",
    ":libs:bre-sdk-java",
)

// services/ — Spring Boot 4.x deployables. Empty shells until M2.
include(
    ":services:decision-service",
    ":services:decision-worker",
    ":services:decision-sink",
    ":services:shadow-evaluator",
    ":services:studio-service",
    ":services:policy-registry",
    ":services:simulation-service",
    ":services:tenant-admin-service",
)

include(":tools:bre-cli")
include(":tests:engine-conformance")
include(":tests:perf")

// apps/studio-web is an npm project, not a Gradle module (LLD §1.1).
