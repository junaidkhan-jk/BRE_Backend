// LLD §1.2: engine-core (value types), jackson-core, a JSON Schema
// validator. No Spring.
plugins { id("bre.java-library") }

dependencies {
    api(project(":libs:bre-engine-core"))
    // api, not implementation: FactsValidator.validate takes a JsonNode, so the type is part of
    // this library's surface and every caller needs it on its own compile classpath.
    api(libs.jackson.databind)
    implementation(libs.jackson.core)
    implementation(libs.json.schema.validator)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
