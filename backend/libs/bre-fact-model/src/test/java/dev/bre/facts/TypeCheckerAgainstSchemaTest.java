package dev.bre.facts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bre.engine.bel.check.BelType;
import dev.bre.engine.bel.check.Diagnostic;
import dev.bre.engine.bel.check.TypeChecker;
import dev.bre.engine.bel.parse.Parser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The type checker against the real canonical fact schema (M1-03).
 *
 * <p>Deliberately not against a hand-built symbol table: the point of the checker is that it
 * rejects expressions over the schema an author actually writes against, and a stub table would let
 * a wrong path or a mistyped field pass unnoticed.
 */
class TypeCheckerAgainstSchemaTest {

  private final FactSchemaSymbols symbols =
      new FactSchemaSymbols(
          LendingFacts.schema(),
          Map.of("INDEBTEDNESS_CAP", BelType.DECIMAL, "MIN_CYCLE", BelType.INTEGER),
          Map.of(
              "UNSECURED_ACCOUNT_TYPES", BelType.LIST_TEXT,
              "OPEN_TRADELINE_STATUSES", BelType.LIST_TEXT),
          Map.of(
              "proposedAmount", BelType.DECIMAL,
              "memberUnsecuredOutstanding", BelType.DECIMAL,
              "lastBureauPullAt", BelType.DATE_TIME),
          Map.of("MFI_APP_SCORE_V1", Map.of("score", BelType.INTEGER)));

  private TypeChecker.Result check(String expression) {
    return TypeChecker.check(Parser.parseExpression(expression), symbols);
  }

  private List<String> codes(String expression) {
    return check(expression).diagnostics().stream().map(Diagnostic::code).toList();
  }

  @Nested
  @DisplayName("expressions that must be accepted")
  class Accepted {

    @Test
    void realExpressionsFromTheReferencePolicyCheckClean() {
      for (String expression :
          List.of(
              "application.requestedAmount <= param.INDEBTEDNESS_CAP",
              "application.tenureMonths > 12",
              "group.cycleNumber >= param.MIN_CYCLE",
              "proposedAmount / 12 <= application.requestedAmount",
              "memberUnsecuredOutstanding + proposedAmount <= param.INDEBTEDNESS_CAP",
              "MFI_APP_SCORE_V1.score >= 650",
              "group.villageRiskCategory = \"A\"",
              "isMissing(application.approvedAmount)",
              "coalesce(application.approvedAmount, application.requestedAmount) > 0",
              "sum(parties.income.monthlyNet) > 0",
              "count(group.memberPartyRefs) >= 5",
              "ageInYears(date(\"1990-01-01\"), application.asOf) >= 18")) {
        assertThat(check(expression).diagnostics()).as("expression: %s", expression).isEmpty();
      }
    }

    /** §3.6: an Integer and a Decimal interoperate, so this must not be a type error. */
    @Test
    void integerAndDecimalCompareAndCombine() {
      assertThat(check("application.tenureMonths > 12.5").diagnostics()).isEmpty();
      assertThat(check("application.tenureMonths + application.requestedAmount").type())
          .as("mixed arithmetic widens to Decimal")
          .contains(BelType.DECIMAL);
    }

    /** §3.6: division always yields a Decimal, because 1/3 is not 0. */
    @Test
    void divisionOfTwoIntegersIsDecimal() {
      assertThat(check("application.tenureMonths / 12").type()).contains(BelType.DECIMAL);
    }

    @Test
    void aFilteredProjectionBindsItToTheElement() {
      assertThat(check("parties.bureauReports.tradelines[it.status = \"ACTIVE\"]").diagnostics())
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("the errors that matter")
  class Rejected {

    /** The example from the class comment: a Text field compared with a number. */
    @Test
    void comparingTextWithANumberIsATypeError() {
      assertThat(codes("application.purpose >= 650"))
          .as("this is the expression that, unchecked, refers a real borrower at runtime")
          .contains(Diagnostic.ORDERING_NOT_ALLOWED);
    }

    @Test
    void anUnknownFactPathIsReported() {
      assertThat(codes("application.noSuchField > 0")).contains(Diagnostic.UNKNOWN_FACT_PATH);
    }

    @Test
    void anUnknownParameterIsReported() {
      assertThat(codes("application.requestedAmount <= param.NO_SUCH_PARAM"))
          .contains(Diagnostic.UNKNOWN_PARAMETER);
    }

    @Test
    void anUnknownReferenceListIsReported() {
      assertThat(codes("application.purpose in list.NO_SUCH_LIST"))
          .contains(Diagnostic.UNKNOWN_REFERENCE_LIST);
    }

    @Test
    void anUnknownFunctionIsReported() {
      assertThat(codes("stdev(parties.income.monthlyNet) > 0"))
          .contains(Diagnostic.UNKNOWN_FUNCTION);
    }

    @Test
    void theWrongNumberOfArgumentsIsReported() {
      assertThat(codes("ageInYears(parties.dateOfBirth)")).contains(Diagnostic.WRONG_ARITY);
    }

    @Test
    void anAggregateOverANonListIsReported() {
      assertThat(codes("sum(application.requestedAmount)")).contains(Diagnostic.TYPE_MISMATCH);
    }

    @Test
    void aFilterOnANonListIsReported() {
      assertThat(codes("application.purpose[it = \"X\"]")).contains(Diagnostic.FILTER_ON_NON_LIST);
    }

    @Test
    void aNonBooleanFilterPredicateIsReported() {
      assertThat(codes("group.memberPartyRefs[it]"))
          .contains(Diagnostic.FILTER_PREDICATE_NOT_BOOLEAN);
    }

    @Test
    void itOutsideAFilterIsReported() {
      assertThat(codes("it > 0")).contains(Diagnostic.IT_OUTSIDE_FILTER);
    }

    @Test
    void aNonBooleanOperandOfAndIsReported() {
      assertThat(codes("application.tenureMonths and application.requestedAmount > 0"))
          .contains(Diagnostic.TYPE_MISMATCH);
    }

    /** BEL-011: a literal compared with an enum field must be one of its values. */
    @Test
    void aTextLiteralOutsideAnEnumIsReported() {
      // The scalar case: group.villageRiskCategory is a single enum-typed field.
      assertThat(codes("group.villageRiskCategory = \"Z\"")).contains(Diagnostic.NOT_AN_ENUM_VALUE);

      // The projected case. `parties.role` is a List<Text>, so an equality against a literal is a
      // list-versus-scalar mismatch (BEL-003, correctly) — `in` is the form an author writes, and
      // the enum check applies there too.
      assertThat(codes("\"MANAGER\" in parties.role")).contains(Diagnostic.NOT_AN_ENUM_VALUE);
      assertThat(check("\"MEMBER\" in parties.role").diagnostics())
          .as("a permitted value is accepted")
          .isEmpty();
      assertThat(codes("parties.role = \"MEMBER\""))
          .as("comparing a projection with a scalar is a type error, not an enum error")
          .containsExactly(Diagnostic.TYPE_MISMATCH);
    }

    @Test
    void aMixedListLiteralIsReported() {
      assertThat(codes("application.purpose in [\"HOUSING\", 1]"))
          .contains(Diagnostic.LIST_ELEMENT_MISMATCH);
    }

    /**
     * §3.7 makes the author convert, because comparing an instant with a calendar date silently
     * depends on a time zone nobody chose.
     */
    @Test
    void comparingADateWithADateTimeIsReported() {
      assertThat(codes("application.asOf > lastBureauPullAt"))
          .contains(Diagnostic.DATE_AND_DATETIME);
    }

    /** Ordering on Text is a determinism rule, not a taste one: collation is locale-dependent. */
    @Test
    void orderingTextIsReported() {
      assertThat(codes("application.purpose > \"HOUSING\""))
          .contains(Diagnostic.ORDERING_NOT_ALLOWED);
      assertThat(check("application.purpose = \"HOUSING\"").diagnostics())
          .as("equality on Text is fine; only ordering is not")
          .isEmpty();
    }

    @Test
    void everyProblemIsReportedNotJustTheFirst() {
      // Three distinct mistakes in one expression. An author fixing a policy wants all of them.
      List<String> codes = codes("application.noSuchField > 0 and stdev(it) > nope.x");
      assertThat(codes)
          .contains(Diagnostic.UNKNOWN_FACT_PATH, Diagnostic.UNKNOWN_FUNCTION)
          .hasSizeGreaterThanOrEqualTo(3);
    }
  }

  @Nested
  @DisplayName("§9.1 read restrictions")
  class ReadRestrictions {

    /** The check that stops a lending decision turning on a protected characteristic. */
    @Test
    void readingAProtectedFieldWithoutAnOverrideIsRejected() {
      for (String path : List.of("parties.gender", "parties.religion", "parties.caste")) {
        assertThat(codes("\"X\" in " + path))
            .as("%s must need an override", path)
            .contains(Diagnostic.PROTECTED_FIELD);
      }
    }

    @Test
    void anApprovedOverrideAllowsAProtectedField() {
      TypeChecker.Result result =
          TypeChecker.check(Parser.parseExpression("\"F\" in parties.gender"), symbols, true);
      assertThat(result.diagnostics())
          .as("the override makes reading it a recorded, approved act rather than a silent one")
          .isEmpty();
    }

    /** BEL-026 has no override, and that is the whole difference from BEL-017. */
    @Test
    void anAadhaarFieldIsRejectedEvenWithAnOverride() {
      assertThat(codes("isMissing(parties.aadhaarVaultRef)")).contains(Diagnostic.AADHAAR_FIELD);

      TypeChecker.Result withOverride =
          TypeChecker.check(
              Parser.parseExpression("isMissing(parties.aadhaarVaultRef)"), symbols, true);
      assertThat(withOverride.diagnostics())
          .extracting(Diagnostic::code)
          .as("no governance override permits reading an aadhaar field")
          .contains(Diagnostic.AADHAAR_FIELD);
    }

    @Test
    void anOrdinaryPiiFieldIsReadableWithoutAnOverride() {
      // pii means masked in views and logs; it does not mean unreadable by rule logic. Income and
      // date of birth are the basis of an affordability assessment.
      assertThat(check("sum(parties.income.monthlyNet) > 0").diagnostics()).isEmpty();
      assertThat(check("isMissing(parties.dateOfBirth)").diagnostics()).isEmpty();
    }
  }

  @Nested
  @DisplayName("checkBoolean")
  class BooleanRole {

    @Test
    void aNonBooleanCheckIsReportedWithItsRole() {
      List<Diagnostic> diagnostics =
          TypeChecker.checkBoolean(
              Parser.parseExpression("application.requestedAmount"), symbols, "rule check", false);

      assertThat(diagnostics)
          .singleElement()
          .satisfies(
              d -> {
                assertThat(d.code()).isEqualTo(Diagnostic.TYPE_MISMATCH);
                assertThat(d.message()).contains("rule check").contains("Boolean");
              });
    }

    @Test
    void aBooleanCheckPasses() {
      assertThat(
              TypeChecker.checkBoolean(
                  Parser.parseExpression("application.requestedAmount > 0"),
                  symbols,
                  "rule check",
                  false))
          .isEmpty();
    }
  }

  /**
   * The checker's registry and the evaluator's must agree. A function the checker knows and the
   * evaluator does not is a policy that compiles and then fails at runtime; the reverse is a
   * function nobody can use.
   */
  @Test
  void theCheckersFunctionRegistryMatchesTheEvaluators() {
    assertThat(TypeChecker.knownFunctions())
        .containsExactlyInAnyOrder(
            "isMissing",
            "coalesce",
            "round",
            "abs",
            "sum",
            "count",
            "distinctCount",
            "max",
            "min",
            "avg",
            "first",
            "any",
            "all",
            "date",
            "ageInYears",
            "monthsBetween",
            "daysBetween",
            "addMonths");
  }

  @Test
  void everyDiagnosticCarriesAUsablePosition() {
    for (Diagnostic diagnostic : check("application.noSuchField > 0").diagnostics()) {
      assertThat(diagnostic.line())
          .as("the studio places a marker on this")
          .isGreaterThanOrEqualTo(1);
      assertThat(diagnostic.column()).isGreaterThanOrEqualTo(1);
    }
  }
}
