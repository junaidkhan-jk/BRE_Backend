package dev.bre.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves every LLD §1.2 architecture rule both holds over production code and <em>fires</em> over a
 * deliberate violation.
 *
 * <p>The second half is the half that matters. A rule asserted only against clean code passes
 * whether or not it works, which makes a green build evidence of nothing. Each rule here is run
 * against a fixture in {@code dev.bre.engine.archfixture} that breaks it on purpose, and the test
 * fails if the rule stays quiet.
 */
class EngineArchRulesTest {

  /** Production classes only: {@code libs/bre-engine-core/src/main}. */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(EngineArchRules.ENGINE_PACKAGE.replace("..", ""));

  /** The deliberate-violation fixtures, which live in the test source set. */
  private static final JavaClasses FIXTURE_CLASSES =
      new ClassFileImporter().importPackages("dev.bre.engine.archfixture");

  /**
   * The engine has no production classes until M1-01. A rule checked against an empty set would
   * either fail ArchUnit's empty-should guard or pass vacuously; neither is informative. These
   * tests report as SKIPPED until there is code to check, and start biting the moment there is.
   */
  private static void assumeEngineCodeExists() {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        PRODUCTION_CLASSES.isEmpty(),
        "no dev.bre.engine production classes yet — M1-01 lands the value model");
  }

  private static void assertRuleFires(
      ArchRule rule, JavaClasses classes, String expectedInMessage) {
    assertThatThrownBy(() -> rule.check(classes))
        .as("rule must report the deliberate violation, not stay silent")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(expectedInMessage);
  }

  @Nested
  @DisplayName("LLD §1.2 rule 1 — determinism (D8): no clock, no randomness")
  class Determinism {

    @Test
    void holdsOverProductionCode() {
      assumeEngineCodeExists();
      EngineArchRules.noClockOrRandomness().check(PRODUCTION_CLASSES);
    }

    @Test
    void firesOnAWallClockRead() {
      assertRuleFires(EngineArchRules.noClockOrRandomness(), FIXTURE_CLASSES, "ClockViolation");
    }

    @Test
    void firesOnRandomness() {
      assertRuleFires(
          EngineArchRules.noClockOrRandomness(), FIXTURE_CLASSES, "RandomnessViolation");
    }
  }

  @Nested
  @DisplayName("LLD §1.2 rule 3 — exceptions carry fact paths and types, never values")
  class ExceptionsCarryNoFactValues {

    @Test
    void holdsOverProductionCode() {
      assumeEngineCodeExists();
      EngineArchRules.exceptionsNeverCarryFactValues().check(PRODUCTION_CLASSES);
    }

    @Test
    void firesOnAnExceptionTakingAFactValue() {
      assertRuleFires(
          EngineArchRules.exceptionsNeverCarryFactValues(),
          FIXTURE_CLASSES,
          "FactValueExceptionViolation");
    }
  }

  @Nested
  @DisplayName("LLD §1.2 dependency matrix — engine-core uses the JDK and re2j only")
  class EngineDependencies {

    @Test
    void holdsOverProductionCode() {
      assumeEngineCodeExists();
      EngineArchRules.engineDependsOnJdkAndRe2jOnly().check(PRODUCTION_CLASSES);
    }
  }

  @Test
  @DisplayName(
      "the fixtures are actually on the classpath, so a silent rule cannot pass by default")
  void fixturesAreImported() {
    assertThat(FIXTURE_CLASSES)
        .as("an empty fixture import would make every firesOn… test vacuous")
        .isNotEmpty();
  }
}
