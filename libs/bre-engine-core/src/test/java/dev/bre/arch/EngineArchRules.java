package dev.bre.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * The architecture rules from LLD §1.2, defined once so the same rule object can be asserted twice:
 * it must pass over the production classes, and it must fail over a fixture that deliberately
 * violates it. A rule with no failing fixture proves nothing, so {@code EngineArchRulesTest} runs
 * both directions for every rule here.
 *
 * <p>Types are matched by simple name rather than by class literal on purpose. The engine types
 * these rules protect ({@code RecordV}, {@code FactsDocument}) do not exist yet in M0, and a rule
 * that cannot be written until the code it guards exists is a rule that arrives too late.
 */
public final class EngineArchRules {

  /** Package under which all engine production code lives. */
  public static final String ENGINE_PACKAGE = "dev.bre.engine..";

  /**
   * Determinism (D8). Evaluation never reads the wall clock and never uses randomness; it uses only
   * {@code asOf}. LLD §1.2 rule 1.
   */
  private static final Set<String> BANNED_DETERMINISM_OWNERS =
      Set.of("java.time.Clock", "java.util.Random", "java.util.concurrent.ThreadLocalRandom");

  private static final Set<String> BANNED_DETERMINISM_CALLS =
      Set.of(
          "java.lang.System.currentTimeMillis()",
          "java.lang.System.nanoTime()",
          "java.time.Instant.now()",
          "java.time.LocalDate.now()",
          "java.time.LocalDateTime.now()",
          "java.time.ZonedDateTime.now()",
          "java.time.OffsetDateTime.now()",
          "java.time.LocalTime.now()",
          "java.time.Year.now()",
          "java.time.YearMonth.now()");

  /**
   * Values that must never leave the engine inside an exception or a log line. Fact paths and types
   * are allowed; fact values are not. LLD §1.2 rule 3.
   */
  private static final Set<String> FACT_VALUE_TYPE_NAMES =
      Set.of(
          "FactsDocument",
          "RecordV",
          "JsonNode",
          "ListV",
          "DecimalV",
          "TextV",
          "DateV",
          "DateTimeV",
          "PeriodV",
          "BoolV",
          "IntV",
          "Value");

  private EngineArchRules() {}

  /** LLD §1.2 rule 1: no clock, no randomness, anywhere under {@code dev.bre.engine..}. */
  public static ArchRule noClockOrRandomness() {
    return noClasses()
        .should(accessAClockOrRandomnessSource())
        .because(
            "evaluation must be a pure function of (facts, bundle, asOf) (D8): no wall clock, "
                + "no randomness. Champion/challenger uses hash(tenantId, applicationRef, "
                + "experimentId) mod 10000, not Random.");
  }

  private static ArchCondition<JavaClass> accessAClockOrRandomnessSource() {
    return new ArchCondition<>("access a wall clock or a randomness source") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaAccess<?> access : item.getAccessesFromSelf()) {
          String owner = access.getTargetOwner().getFullName();
          String call = owner + "." + access.getTarget().getName() + "()";
          if (BANNED_DETERMINISM_OWNERS.contains(owner)
              || BANNED_DETERMINISM_CALLS.contains(call)) {
            events.add(
                SimpleConditionEvent.satisfied(
                    item,
                    item.getName()
                        + " reaches "
                        + call
                        + " at "
                        + access.getSourceCodeLocation()
                        + "; use asOf, or a hash, instead"));
          }
        }
      }
    };
  }

  /**
   * LLD §1.2 rule 3: engine exceptions carry fact <em>paths</em> and <em>types</em>, never values.
   * Expressed as: no exception constructor accepts a fact-value type.
   */
  public static ArchRule exceptionsNeverCarryFactValues() {
    return noClasses()
        .that()
        .areAssignableTo(Throwable.class)
        .should(haveConstructorParameterOfFactValueType())
        .because(
            "an exception message or field holding a fact value leaks PII into stack traces and "
                + "logs; exceptions carry the fact path and its type instead (LLD §1.2 rule 3)");
  }

  private static ArchCondition<JavaClass> haveConstructorParameterOfFactValueType() {
    return new ArchCondition<>("have a constructor parameter of a fact-value type") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaConstructor constructor : item.getConstructors()) {
          for (JavaType parameterType : constructor.getParameterTypes()) {
            String simpleName = simpleNameOf(parameterType);
            if (FACT_VALUE_TYPE_NAMES.contains(simpleName)) {
              events.add(
                  SimpleConditionEvent.satisfied(
                      constructor,
                      constructor.getFullName()
                          + " takes a fact value of type "
                          + simpleName
                          + "; pass the fact path and type instead"));
            }
          }
        }
      }
    };
  }

  private static String simpleNameOf(JavaType type) {
    JavaType erased = type instanceof JavaParameterizedType p ? p.toErasure() : type;
    String name = erased.getName();
    int lastDot = name.lastIndexOf('.');
    String simple = lastDot < 0 ? name : name.substring(lastDot + 1);
    int lastDollar = simple.lastIndexOf('$');
    return lastDollar < 0 ? simple : simple.substring(lastDollar + 1);
  }

  /**
   * LLD §1.2 dependency matrix for {@code bre-engine-core}: the JDK and {@code re2j}, and nothing
   * else. Spring, Jackson, logging backends and I/O are all out.
   */
  public static ArchRule engineDependsOnJdkAndRe2jOnly() {
    List<String> allowedPackages =
        List.of(
            "dev.bre.engine..",
            "java..",
            "javax..",
            "jdk..",
            "com.google.re2j..",
            // test-only, present solely in the test source set
            "org.junit..",
            "org.assertj..",
            "net.jqwik..",
            "com.tngtech.archunit..");

    return noClasses()
        .that()
        .resideInAPackage(ENGINE_PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages(allowedPackages.toArray(String[]::new))
        .because(
            "bre-engine-core is a pure library (D3): JDK 25 plus re2j. No Spring, no Jackson, "
                + "no logging backend, no I/O (LLD §1.2).");
  }
}
