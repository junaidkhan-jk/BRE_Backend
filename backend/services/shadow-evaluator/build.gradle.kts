// Empty shell until M2, which applies bre.spring-service (Spring Boot 4.x +
// the Nexus repository for trustt-platform-lib). Until there is a main class,
// the library conventions are all this module needs.
plugins { id("bre.java-library") }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
