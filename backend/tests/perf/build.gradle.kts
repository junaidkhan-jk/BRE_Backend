import net.ltgt.gradle.errorprone.errorprone

// JMH benchmarks for the engine (LLD §1.1, M1-16).
//
// Wired by hand rather than with a JMH Gradle plugin: all the plugin does is declare the source
// set, add the annotation processor and give you a run task, which is the twenty lines below. One
// fewer third-party build plugin on a build whose containment rule (D11) is the point.
plugins { id("bre.java-library") }

sourceSets {
    create("jmh") {
        java.srcDir("src/jmh/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

val jmhImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    implementation(project(":libs:bre-engine-core"))

    jmhImplementation(project(":libs:bre-engine-core"))
    jmhImplementation(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// JMH's annotation processor generates a harness class per benchmark, full of the deliberate
// spin-waits and Thread.yield() calls a microbenchmark needs. Error Prone flags those, and with
// -Werror they fail a build over code nobody here wrote and nobody can fix. Analysis is therefore
// off for the generated source set only; every hand-written source set keeps it.
tasks.named<JavaCompile>("compileJmhJava") {
    options.errorprone.enabled.set(false)
    options.compilerArgs.removeAll(listOf("-Werror"))
}

// ./gradlew :tests:perf:jmh                      -- every benchmark
tasks.named<JavaCompile>("compileJmhJava") {
    options.errorprone.enabled.set(false)
    options.compilerArgs.removeAll(listOf("-Werror"))
}

// ./gradlew :tests:perf:jmh --args="Decision"    -- one, by regex
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the engine JMH benchmarks."
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["jmh"].runtimeClasspath
    // JMH forks a JVM per benchmark, and the fork inherits the toolchain rather than the daemon's
    // JVM — which matters here because the daemon runs on 21 and the engine compiles to 25.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
