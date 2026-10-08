// LLD §1.2: contracts, optionally engine-core + ir-json + bundle. The core
// SDK MUST NOT depend on Spring; a separate bre-sdk-spring adapter may exist.
plugins { id("bre.java-library") }

dependencies {
    api(project(":libs:bre-contracts"))
    compileOnly(project(":libs:bre-engine-core"))
    compileOnly(project(":libs:bre-ir-json"))
    compileOnly(project(":libs:bre-bundle"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
