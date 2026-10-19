// LLD §1.2: engine-core, ir-json, JDK java.security (Ed25519, SHA-256).
// No Spring.
plugins { id("bre.java-library") }

dependencies {
    // Coverage-guided fuzzing of the untrusted input this module parses (M6-02).
    testImplementation(libs.jazzer.junit)
    api(project(":libs:bre-engine-core"))
    api(project(":libs:bre-ir-json"))
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
