// LLD §1.2: JDK javax.crypto, zstd-jni, a KMS client port (interface only).
// MUST NOT depend on engine-core.
plugins { id("bre.java-library") }

dependencies {
    implementation(libs.zstd.jni)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}
