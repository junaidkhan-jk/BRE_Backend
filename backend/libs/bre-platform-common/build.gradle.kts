// Tenancy context, UUIDv7, problem+json, OTel helpers, PII log guard
// (LLD §1.1).
//
// Measured against trustt-platform-lib (ADR-016): of those five, only TENANCY
// CONTEXT actually collides — that library has ThreadLocalContext.setTenant,
// but no UUIDv7/correlation helper, no problem+json (it uses a custom
// {"response_status":{...}} envelope, incompatible with LLD §6.6), no
// OpenTelemetry (Elastic APM only), and no automatic PII log guard (only
// opt-in MaskStringUtil that a caller must remember to call). So this module
// keeps four of its five jobs outright, and M2's decision narrows to tenancy
// alone. Left with no dependencies until that is settled, so the choice is
// not pre-empted here.
plugins { id("bre.java-library") }

dependencies {
    // The OpenTelemetry API, never the SDK. The API is a no-op until an application installs an
    // SDK, so a library can carry it without deciding anyone's exporter or sampler.
    api(platform(libs.opentelemetry.bom))
    api(libs.opentelemetry.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
