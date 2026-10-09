package dev.bre.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Tenant scoping (D1, LLD §5.1). */
class TenantContextTest {

  @Test
  void aTenantIsVisibleInsideItsScope() {
    TenantContext.runIn("t_acme", () -> assertThat(TenantContext.require()).isEqualTo("t_acme"));
  }

  /**
   * The failure this class exists to prevent: an unset context must not resolve to anything.
   * Returning a default here is how one lender's data reaches another.
   */
  @Test
  void anUnboundContextThrowsRatherThanDefaulting() {
    assertThat(TenantContext.isBound()).isFalse();
    assertThat(TenantContext.current()).isEmpty();
    assertThatThrownBy(TenantContext::require)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no tenant is bound");
  }

  @Test
  void theBindingIsRestoredAfterTheScopeEnds() {
    TenantContext.runIn(
        "t_outer",
        () -> {
          TenantContext.runIn(
              "t_inner", () -> assertThat(TenantContext.require()).isEqualTo("t_inner"));
          assertThat(TenantContext.require())
              .as("the inner scope must not overwrite the outer one")
              .isEqualTo("t_outer");
        });
    assertThat(TenantContext.isBound()).as("and nothing leaks past the outermost scope").isFalse();
  }

  @Test
  void theBindingIsRestoredEvenWhenTheActionThrows() {
    TenantContext.runIn(
        "t_outer",
        () -> {
          assertThatThrownBy(
                  () ->
                      TenantContext.runIn(
                          "t_inner",
                          () -> {
                            throw new IllegalStateException("boom");
                          }))
              .isInstanceOf(IllegalStateException.class);
          assertThat(TenantContext.require()).isEqualTo("t_outer");
        });
  }

  // NOT TESTED HERE: inheritance by a structured-concurrency fork, which is the reason
  // TenantContext is a ScopedValue rather than a ThreadLocal — fanning out to fetch two bureau
  // reports in parallel must not drop the tenant, and a ThreadLocal would, silently, in the
  // forked task. StructuredTaskScope is still a preview API in Java 25, and enabling preview
  // build-wide would pin the exact JVM version and change the compilation contract for every
  // module, which is too much to spend on one test. The direction the platform depends on today
  // is the opposite one, and anUnrelatedThreadSeesNoTenant below does assert it: a task outside
  // the scope inherits nothing. Revisit when structured concurrency is final.

  /** A plain thread is a different scope and inherits nothing — which is the safe direction. */
  @Test
  void anUnrelatedThreadSeesNoTenant() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Boolean boundElsewhere =
          TenantContext.runIn("t_acme", () -> executor.submit(TenantContext::isBound).get());
      assertThat(boundElsewhere)
          .as("a task submitted to a pool is outside the scope, and must not inherit the tenant")
          .isFalse();
    }
  }

  @Test
  void aCallableScopeReturnsItsValue() {
    assertThat(TenantContext.runIn("t_acme", () -> TenantContext.require() + "/ok"))
        .isEqualTo("t_acme/ok");
  }

  @Test
  void aMalformedTenantIdIsRejectedAtTheBoundary() {
    // "acme" is NOT here, and that is the correction: §2.2 $defs/tenantId is
    // ^[a-z][a-z0-9_]{2,31}$ with no t_ prefix, so a tenant called acme or hdfc_bank is valid.
    // This test previously asserted the opposite and locked in a bug.
    for (String invalid : new String[] {"", "  ", "ab", "T_ACME", "t_acme-1", "1acme", "_acme"}) {
      assertThatThrownBy(() -> TenantContext.runIn(invalid, () -> {}))
          .as("'%s' must be rejected", invalid)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> TenantContext.runIn(null, () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aWellFormedTenantIdIsAccepted() {
    for (String valid : new String[] {"t_acme", "acme", "hdfc_bank", "t_acme_bank_01", "abc"}) {
      TenantContext.runIn(valid, () -> assertThat(TenantContext.require()).isEqualTo(valid));
    }
  }
}
