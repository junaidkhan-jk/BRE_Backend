// JMH (engine) and k6/chaos scripts (LLD §1.1). The JMH budgets land in
// M1-16; this module exists so the source set and CI wiring are in place.
plugins { id("bre.java-library") }

dependencies {
    implementation(project(":libs:bre-engine-core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
