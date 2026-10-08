// psf lint | compile | test | bundle | verify — local and offline (M1-15).
plugins { id("bre.java-library") }

dependencies {
    implementation(project(":libs:bre-engine-core"))
    implementation(project(":libs:bre-ir-json"))
    implementation(project(":libs:bre-bundle"))
    implementation(project(":libs:bre-fact-model"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
