// LLD §1.2: MAY depend on JDK 25 and re2j. MUST NOT depend on Spring,
// Jackson, any logging backend, or any I/O. Enforced by ArchUnit too.
plugins { id("bre.java-library") }

dependencies {
    api(libs.re2j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testImplementation(libs.archunit.junit5)
    // Jazzer is a TEST dependency only. The engine itself stays on the JDK and re2j (LLD §1.2),
    // and the ArchUnit purity rule covers main sources, so this cannot leak into the engine.
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
