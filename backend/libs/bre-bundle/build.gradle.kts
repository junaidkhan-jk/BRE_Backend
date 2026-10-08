// LLD §1.2: engine-core, ir-json, JDK java.security (Ed25519, SHA-256).
// No Spring.
plugins { id("bre.java-library") }

dependencies {
    api(project(":libs:bre-engine-core"))
    api(project(":libs:bre-ir-json"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
