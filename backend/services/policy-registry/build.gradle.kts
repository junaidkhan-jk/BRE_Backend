// Owns the bre_ctl migrations (LLD §5.2). The control-plane schema is shared by
// studio-service, policy-registry, simulation-service and tenant-admin-service, so one module has
// to own its migrations rather than four each carrying a copy; policy-registry is the M2-era
// control-plane service and takes them.
//
// M2-03: the service itself. It owns the policy-version lifecycle (00 §3.3), compiles and signs a
// bundle on approval, and publishes the Active Pointer Manifest the decision pods read (§8.1).
//
// Deliberately does NOT apply bre.spring-service, for the reasons ADR-016 records: that plugin
// carries the Nexus repository for trustt-platform-lib, and the Trustt convention plugins set
// source/target compatibility to 17 imperatively, which would silently drop this service off the
// Java 25 toolchain.
plugins {
    id("bre.java-library")
    alias(libs.plugins.spring.boot)
}

sourceSets {
    // A local-development entry point that starts a real PostgreSQL and blocks. Its own source set
    // so it is never on the service's runtime classpath: embedded-postgres downloads and runs a
    // database server, which is not something a deployed artefact should be able to do.
    create("devstack") {
        java.srcDir("src/devstack/java")
    }
}

val devstackImplementation: Configuration by configurations.getting

dependencies {
    // The compile pipeline: PSF in, IR out, static checks, and the engine to run test cases with.
    implementation(project(":libs:bre-ir-json"))
    implementation(project(":libs:bre-engine-core"))
    implementation(project(":libs:bre-fact-model"))
    // Approval builds and SIGNS the bundle, and a deployment signs the manifest.
    implementation(project(":libs:bre-bundle"))
    implementation(project(":libs:bre-platform-common"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    // Spring Boot 4 split the monolithic autoconfigure module: Flyway's auto-configuration lives
    // in its own artifact now, and without it flyway-core sits on the classpath and never runs.
    // That failure is silent — the service starts, every query then fails with "relation does not
    // exist", and the first place to look is the migration scripts, which are fine.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    // Verifies an identity provider's JWTs (M3-09). Not the oauth2-resource-server starter — see
    // the note on the version in libs.versions.toml.
    implementation(libs.nimbus.jose.jwt)
    // The OpenAPI document and the Swagger UI, generated from the mappings (M6-05).
    implementation(libs.springdoc.webmvc.ui)
    runtimeOnly(libs.postgresql)

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // The test mints its own RSA-signed JWTs and serves its own JWKS, so a forged signature, a
    // wrong issuer and a wrong audience are each refused by the real verifier rather than by a
    // stub asserting my assumptions.
    testImplementation(libs.nimbus.jose.jwt)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Runs a REAL PostgreSQL. This host has no Docker, so Testcontainers is not an option
    // locally; embedded-postgres downloads an actual postgres binary from Maven Central and runs
    // it. That keeps the D11 containment intact — it is a build dependency, not a service this
    // build has to reach over the network at test time.
    testImplementation(libs.postgresql)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))

    devstackImplementation(libs.embedded.postgres)
    devstackImplementation(platform(libs.embedded.postgres.binaries.bom))
    devstackImplementation(libs.postgresql)
    devstackImplementation(libs.flyway.core)
    devstackImplementation(libs.flyway.postgresql)
}

// ./gradlew :services:policy-registry:devPostgres --args="var/pg.properties"
//
// Starts a real PostgreSQL and blocks. devstack/up.sh runs this in the background, waits for the
// properties file, migrates, and then starts the registry against it.
tasks.register<JavaExec>("devPostgres") {
    group = "application"
    description = "Starts an embedded PostgreSQL for local development, and blocks."
    mainClass.set("dev.bre.registry.devstack.EmbeddedPostgresMain")
    classpath = sourceSets["devstack"].runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    workingDir = rootProject.layout.projectDirectory.asFile
}
