// The sync Decision API (LLD §6.2) — the walking skeleton's one deployable (M2).
//
// Deliberately does NOT apply bre.spring-service yet. That plugin carries the Nexus repository
// for trustt-platform-lib, and ADR-016 records two traps it brings: the Trustt convention plugins
// set source/target compatibility to 17 imperatively, which would silently drop this service from
// Java 25, and there is no generic dependency plugin to apply in the first place. Plain Spring
// Boot from Maven Central keeps this runnable today; adopting platform-lib is a separate,
// per-service decision.
plugins {
    id("bre.java-library")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Declared explicitly rather than taken from a starter's transitives: the fact snapshot is
    // stored verbatim on the decision record, so the JSON codec is a direct dependency of the
    // decision path, and its version belongs in the catalogue with everything else.
    implementation(libs.jackson.databind)
    // Spring Boot 4 split Jackson auto-configuration out of starter-web, so the ObjectMapper
    // bean the decision path needs is not there by default.
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    implementation(project(":libs:bre-engine-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Virtual threads (ADR-012); surface any pinning in tests rather than in production.
    jvmArgs("-Djdk.tracePinnedThreads=short")
}
