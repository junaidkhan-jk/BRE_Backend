// LLD §1.2: engine-core, jackson, snakeyaml-engine (YAML 1.2). No Spring.
plugins { id("bre.java-library") }

dependencies {
    api(project(":libs:bre-engine-core"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.snakeyaml.engine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
