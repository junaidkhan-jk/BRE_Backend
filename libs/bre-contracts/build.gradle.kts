// OpenAPI 3.1, .proto, AsyncAPI 3.0, event JSON Schemas (LLD §1.1).
plugins { id("bre.contracts") }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
