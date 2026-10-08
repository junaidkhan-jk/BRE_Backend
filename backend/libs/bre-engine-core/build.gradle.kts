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
    testRuntimeOnly(libs.junit.platform.launcher)
}
