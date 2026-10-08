// Tenancy context, UUIDv7, problem+json, OTel helpers, PII log guard
// (LLD §1.1).
//
// OPEN QUESTION for M2: trustt-platform-lib provides all five of these too.
// Two tenancy mechanisms in one service is a defect waiting to happen. M2
// must name one owner per concern before any service applies
// bre.spring-service. Deliberately left with no dependencies until then, so
// the choice is not pre-empted here.
plugins { id("bre.java-library") }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
