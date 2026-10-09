// Language-neutral conformance vectors (JSON) plus the JUnit runner that
// executes them (M0-04). Every engine build MUST pass all of them, and so
// must the studio's TypeScript preview evaluator if one is ever added
// (LLD §13).
plugins { id("bre.java-library") }

dependencies {
    testImplementation(project(":libs:bre-engine-core"))
    // The policy vectors carry IR fragments, which the codec turns into engine records.
    testImplementation(project(":libs:bre-ir-json"))
    // The reference-policy acceptance test reads real facts documents, which have to be typed by
    // the real fact schema — the whole point is that it exercises the same path production does.
    testImplementation(project(":libs:bre-fact-model"))
    testImplementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    // The vectors are data, not classpath resources: the runner reads them
    // from this directory so they can be edited without a rebuild, and so a
    // non-JVM engine can consume the same files.
    systemProperty("bre.conformance.vectors", layout.projectDirectory.dir("vectors").asFile.absolutePath)
    // The PII log guard scans every module's sources, not just this one.
    systemProperty("bre.repo.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    // The reference policy, its assets and its fixtures live outside backend/ (they are product
    // artefacts, not test resources) so the acceptance test is given the repository root.
    systemProperty(
        "bre.reference.policy",
        rootProject.layout.projectDirectory.dir("../reference-policy").asFile.absolutePath)
}
