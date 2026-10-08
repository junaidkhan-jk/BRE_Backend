// Convention plugin for every module under services/.
//
// NOT APPLIED BY ANY MODULE YET. The services are empty shells until M2; a
// Spring Boot application plugin on a module with no main class produces a
// bootJar failure and nothing of value. M2 applies this.
//
// This is the ONLY place the Nexus repository appears. That is what contains
// the deliberate D11 deviation: libs/ and bre-engine-core stay pure and
// Maven-Central-only, while services/ may additionally use
// trustt-platform-lib. See docs/adr/ADR-016-trustt-platform-lib-in-services-only.md.
//
// TWO THINGS M2 MUST SETTLE BEFORE APPLYING THIS — both verified against the
// Trustt repos, not assumed:
//
//  1. THERE IS NO GENERIC DEPENDENCY PLUGIN. Every Trustt service applies its
//     own privately-published `in.novopay:<service>.dependency.gradle.plugin`
//     (trustt-platform-approval/build.gradle:14). The `bre.dependency` id that
//     exists belongs to the OLD trustt-platform-bre service, not to this
//     product. A BRE 2.0 service needs either a new plugin class published to
//     trustt-platform-dependency-mgmt, or direct `in.novopay:infra-*`
//     dependencies declared here without a convention plugin at all. The
//     second option is preferable: it keeps version control in
//     gradle/libs.versions.toml instead of inside an opaque Nexus-hosted jar.
//
//  2. THAT PLUGIN WOULD OVERRIDE THE JAVA 25 TOOLCHAIN. The plugins set
//     sourceCompatibility/targetCompatibility to 17 imperatively
//     (InfraPlatformDependencyPlugin.java:63-64), and they also disable
//     bootJar and reconfigure publishing. Applying one after bre.java-library
//     would silently drop this product from Java 25 (ADR-012) to Java 17 with
//     no error. If a convention plugin is used at all, assert the toolchain
//     after applying it.
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
