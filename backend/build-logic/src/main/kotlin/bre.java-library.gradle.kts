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

// THE THIRD TIME THIS BIT THIS BUILD IS WHY THIS TASK EXISTS.
//
// Spring Boot 4 moved auto-configuration out of the monolithic spring-boot-autoconfigure and into
// one artifact per technology. Declaring the library alone compiles, starts, and does nothing:
//
//   flyway-core without spring-boot-flyway  -> policy-registry started, never migrated, and every
//                                              query failed with "relation does not exist" while
//                                              the scripts were perfectly fine.
//   flyway-core without spring-boot-flyway  -> the SAME defect in decision-service, missed because
//                                              every bre_dec test configures Flyway by hand and so
//                                              never needed the auto-configuration.
//   spring-kafka without spring-boot-kafka  -> no KafkaTemplate bean, so KAFKA mode and the async
//                                              endpoint both failed at startup.
//
// Two of those three were SILENT until the first query. Each was found separately and treated as a
// one-off; the pattern was only obvious on the third. A pair added here costs one line and removes
// a whole class of discovery-by-outage.
//
// Registered per project rather than in the root build, because Gradle forbids resolving another
// project's configuration — which is the right rule and was the first thing this task got wrong.
val springAutoConfigurationPairs = mapOf(
    "org.flywaydb:flyway-core" to "org.springframework.boot:spring-boot-flyway",
    "org.springframework.kafka:spring-kafka" to "org.springframework.boot:spring-boot-kafka",
)

val checkSpringAutoConfigurations = tasks.register("checkSpringAutoConfigurations") {
    group = "verification"
    description =
        "Asserts every Spring technology on this project's runtime classpath also has its " +
            "auto-configuration artifact. Spring Boot 4 split those out, and the failure is silent."

    val configuration = configurations.findByName("runtimeClasspath")
    val resolved = configuration?.incoming?.resolutionResult?.rootComponent
    val projectPath = project.path

    doLast {
        if (resolved == null) {
            return@doLast
        }
        val coordinates = mutableSetOf<String>()
        fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult, seen: MutableSet<String>) {
            val id = component.moduleVersion ?: return
            val key = "${id.group}:${id.name}"
            if (!seen.add(key)) return
            coordinates.add(key)
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected, seen) }
        }
        walk(resolved.get(), mutableSetOf())

        val missing = springAutoConfigurationPairs.entries
            .filter { (library, _) -> coordinates.contains(library) }
            .filterNot { (_, autoConfig) -> coordinates.contains(autoConfig) }
            .map { (library, autoConfig) ->
                "$projectPath: has $library but NOT $autoConfig — the library sits on the " +
                    "classpath and is never configured"
            }

        require(missing.isEmpty()) {
            "A Spring technology is on the classpath without its auto-configuration.\n" +
                "Spring Boot 4 split these out; without the partner artifact the feature is " +
                "silently absent.\n" + missing.joinToString("\n") { "  $it" }
        }
    }
}

tasks.named("check") { dependsOn(checkSpringAutoConfigurations) }
