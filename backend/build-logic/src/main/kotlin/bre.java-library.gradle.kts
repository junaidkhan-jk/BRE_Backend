import net.ltgt.gradle.errorprone.errorprone

// Convention plugin for every module under libs/, tools/ and tests/.
//
// D11 / deviation containment: this plugin declares Maven Central and nothing
// else. The Nexus repository that trustt-platform-lib needs appears only in
// bre.spring-service. A test in :tests:engine-conformance asserts that no
// libs/* project can resolve from any other repository.

plugins {
    id("net.ltgt.errorprone")
    `java-library`
    jacoco
    id("com.diffplug.spotless")
}

group = "dev.bre"

repositories {
    mavenCentral()
}

java {
    toolchain {
        // ADR-012. Gradle provisions the JDK through the foojay resolver
        // declared in settings.gradle.kts; no system install is needed.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Error Prone runs INSIDE javac and reads its internals, so the version has to know the
    // compiler it is given. 2.50.0 is verified working on the Java 25 toolchain below — unlike
    // google-java-format, which still is not (see the spotless block). Pinned here rather than in
    // the version catalogue because a convention plugin in an included build has no `libs`
    // accessor, which is also why spotless pins its own version a few lines down.
    "errorprone"("com.google.errorprone:error_prone_core:2.50.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all",
            "-Werror",
            // The engine uses System.Logger; it has no serialization surface.
            "-Xlint:-serial",
        ),
    )

    options.errorprone {
        // Every finding is an error. The build already runs javac with -Werror, so a warning
        // nobody has to fix is a warning nobody fixes; Error Prone's default severities are
        // chosen to be actionable and this project has no backlog of them to grandfather in.
        allErrorsAsWarnings.set(false)

        // DefaultPackage fires on the Gradle-generated test fixtures and on nothing we write,
        // since every source file here is under dev.bre.
        disable("DefaultPackage")

        // The engine's exhaustive switches over sealed hierarchies are the pattern the codebase
        // relies on for compile-time completeness (see DependencyGraph.collect). Error Prone's
        // UnnecessaryDefaultInEnumSwitch wants a default branch removed, which is right, but
        // StatementSwitchToExpressionSwitch rewrites them wholesale and fights the style.
        disable("StatementSwitchToExpressionSwitch")
    }
}

spotless {
    java {
        // google-java-format drives javac's internal parser, so it only runs on a JVM whose
        // javac internals it knows. No released version runs on JDK 25 yet — 1.37.0 and 1.28.0
        // both fail there with a bare InvocationTargetException. Hence gradle/gradle-daemon-jvm.properties
        // pins the DAEMON to Java 21 while this plugin still compiles to the Java 25 toolchain
        // below (ADR-012). Raise both together once google-java-format supports 25.
        googleJavaFormat("1.28.0")
        targetExclude("**/build/**")
        // .gitkeep placeholders in empty package dirs are not Java sources.
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test>().configureEach {
    // No engine filter. Two failure modes to avoid, both seen on this build:
    //   - Gradle's JvmTestSuite.useJUnitJupiter() pins the suite to the Jupiter engine, which
    //     silently drops every jqwik @Property: the property-based half of the test strategy
    //     reports as "skipped" while the build stays green.
    //   - Naming engines explicitly instead fails the whole test JVM in any module where one of
    //     them is absent ("Could not complete execution for Gradle Test Executor").
    // Letting the platform discover whatever engines are on each module's classpath avoids both.
    useJUnitPlatform()
    // Determinism (D8): tests must not depend on the host locale or zone.
    // Property test 7 asserts bundle hashes are stable under these changing.
    systemProperty("user.timezone", "UTC")
    systemProperty("user.language", "en")
    systemProperty("user.country", "IN")
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
