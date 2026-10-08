// Convention plugin for every module under services/.
//
// NOT APPLIED BY ANY MODULE YET. The services are empty shells until M2; a
// Spring Boot application plugin on a module with no main class produces a
// bootJar failure and nothing of value. M2 applies this.
//
// This is the ONLY place the Nexus repository appears. That is what contains
// the deliberate D11 deviation: libs/ and bre-engine-core stay pure and
// Maven-Central-only, while services/ may additionally use
// trustt-platform-lib. See docs/adr/ADR-016-platform-lib-in-services.md.
//
// Credentials are read from Gradle properties or the environment. They are
// NEVER written into a build file — trustt-platform-bre/build.gradle:6-11
// hardcodes them, and that pattern must not be copied here.

plugins {
    id("bre.java-library")
    id("org.springframework.boot")
    id("org.cyclonedx.bom")
}

repositories {
    mavenCentral()
    maven {
        name = "trusttNexus"
        url = uri("https://nexus.trustt.com/repository/maven-public/")
        credentials {
            username = providers.gradleProperty("trusttNexusUsername")
                .orElse(providers.environmentVariable("TRUSTT_NEXUS_USERNAME"))
                .orNull
            password = providers.gradleProperty("trusttNexusPassword")
                .orElse(providers.environmentVariable("TRUSTT_NEXUS_PASSWORD"))
                .orNull
        }
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
}

tasks.withType<Test>().configureEach {
    // Virtual threads (ADR-012).
    jvmArgs("-Djdk.tracePinnedThreads=short")
}
