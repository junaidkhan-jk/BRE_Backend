package dev.bre.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RFC 9457 problem bodies (LLD §6.6). */
class ProblemJsonTest {

  @Test
  void aProblemCarriesTheTypeUriAndStatus() {
    ProblemJson problem =
        ProblemJson.of("facts-invalid", "The facts document is not valid", 422, "see errors");

    assertThat(problem.type()).isEqualTo("https://bre.dev/problems/facts-invalid");
    assertThat(problem.status()).isEqualTo(422);
    assertThat(problem.instance()).isEmpty();
    assertThat(problem.errors()).isEmpty();
  }

  @Test
  void aTraceIdCanBeAttached() {
    assertThat(ProblemJson.of("facts-invalid", "t", 422, "d").withTrace("01a11b0b-df03").instance())
        .contains("01a11b0b-df03");
  }

  /**
   * §9.6: a field error names the PATH. The value that failed is the one thing it must not carry.
   */
  @Test
  void fieldErrorsNamePathsAndTheCallerSuppliesNoValue() {
    ProblemJson problem =
        ProblemJson.of("facts-invalid", "The facts document is not valid", 422, "2 problems")
            .withFieldError("parties[0].income.monthlyNet", "must be a decimal string")
            .withFieldError("application.tenureMonths", "must be an integer");

    assertThat(problem.errors())
        .containsOnlyKeys("parties[0].income.monthlyNet", "application.tenureMonths");
    assertThat(problem.errors().values())
        .allSatisfy(message -> assertThat(message).doesNotContainPattern("\\d{4,}"));
  }

  @Test
  void aProblemIsImmutableAndBuildersReturnCopies() {
    ProblemJson original = ProblemJson.of("x", "t", 400, "d");
    ProblemJson withError = original.withFieldError("a.b", "bad");

    assertThat(original.errors()).isEmpty();
    assertThat(withError.errors()).containsOnlyKeys("a.b");
    assertThat(original.withTrace("t1").instance()).contains("t1");
    assertThat(original.instance()).isEmpty();
  }

  @Test
  void theMediaTypeIsTheRfcOne() {
    assertThat(ProblemJson.MEDIA_TYPE).isEqualTo("application/problem+json");
  }
}
