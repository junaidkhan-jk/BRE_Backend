// The offline command line.
//
// `facts validate` and `facts schema` are M6-05's "fact-schema validation CLI for their CI".
// `psf lint|compile|test|bundle|verify` is M1-15 and waits on the bundle format (M1-13).
plugins {
    id("bre.java-library")
    // Gives `installDist`, which produces a self-contained bin/bre an integrator can put on a
    // CI image. M6-05's whole point is that their pipeline can run this offline.
    application
}

application {
    mainClass.set("dev.bre.cli.Main")
    applicationName = "bre"
}

dependencies {
    implementation(project(":libs:bre-engine-core"))
    implementation(project(":libs:bre-ir-json"))
    implementation(project(":libs:bre-bundle"))
    implementation(project(":libs:bre-fact-model"))
    implementation(libs.jackson.databind)
    // A CLI that prints SLF4J's "no providers were found" banner to stderr on every run looks
    // broken to the person running it. Binding to no-op says the same thing by saying nothing.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
