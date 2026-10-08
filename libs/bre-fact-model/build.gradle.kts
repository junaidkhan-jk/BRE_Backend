// LLD §1.2: engine-core (value types), jackson-core, a JSON Schema
// validator. No Spring.
plugins { id("bre.java-library") }

dependencies {
    api(project(":libs:bre-engine-core"))
    implementation(libs.jackson.core)
    implementation(libs.json.schema.validator)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
