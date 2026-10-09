// Owns the bre_ctl migrations (LLD §5.2). The control-plane schema is shared by
// studio-service, policy-registry, simulation-service and tenant-admin-service, so one module has
// to own its migrations rather than four each carrying a copy; policy-registry is the M2-era
// control-plane service and takes them.
//
// No main class yet — M2-03 brings the service itself. The migrations and the test that executes
// them come first, because a 760-line DDL no database has ever parsed is not a schema, it is a
// draft.
plugins { id("bre.java-library") }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Runs a REAL PostgreSQL. This host has no Docker, so Testcontainers is not an option
    // locally; embedded-postgres downloads an actual postgres binary from Maven Central and runs
    // it. That keeps the D11 containment intact — it is a build dependency, not a service this
    // build has to reach over the network at test time.
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
}
