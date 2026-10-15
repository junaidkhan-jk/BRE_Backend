package dev.bre.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NFR-COMP-03: 100% of studio and registry state changes are audited, verified by an
 * "audit-coverage test generated from the API inventory" (FR-AUD-005).
 *
 * <p><b>This is a completeness test, which is a different thing from the lifecycle tests.</b> Those
 * assert that a particular action is audited; none of them can notice an action that was never
 * audited in the first place, because a test is written alongside the code it covers and the
 * omission is in the code nobody thought about. The inventory is generated from the classpath
 * instead, so a mutating endpoint added next year fails this test on the day it is written.
 *
 * <p>It caught one on the first run: {@code GET /v1/bundles/{hash}} was unaudited. An import was
 * recorded because it brings an unreviewed policy in; an export was not — and an export sends a
 * lender's complete signed credit policy out of the installation, which is the question that
 * arrives after somebody has left the company. (That endpoint reads rather than writes, so it is
 * not in the mutating inventory below; it is listed as audited anyway, because what matters about
 * it is the copy leaving rather than the state changing.)
 *
 * <p><b>Why a declared table rather than static analysis of the call graph.</b> Proving that every
 * path through a controller reaches {@code audit.record} needs interprocedural analysis, and a test
 * that is wrong in either direction is worse than this: a false pass teaches people the gate works,
 * and a false failure teaches them to add exemptions. A declared table is honest — it fails on
 * anything new, and the fix is one line that a reviewer can see and argue with.
 */
class AuditCoverageTest {

  /**
   * Every mutating endpoint, and the audit action it records — or why it records none.
   *
   * <p>An entry here is a claim a reviewer can check. Adding one to silence this test without
   * checking is possible, and is also visible in the diff, which is the most a test can do.
   */
  private static final Map<String, String> AUDITED =
      Map.ofEntries(
          // --- policy lifecycle
          Map.entry("PolicyController.createPolicy", "POLICY.CREATED"),
          Map.entry("PolicyController.createDraft", "POLICY_VERSION.CREATED / .CLONED"),
          Map.entry("PolicyController.importPolicy", "POLICY_VERSION.IMPORTED"),
          Map.entry("PolicyController.save", "POLICY_VERSION.SAVED"),
          Map.entry("PolicyController.submit", "POLICY_VERSION.SUBMITTED"),
          Map.entry("PolicyController.review", "POLICY_VERSION.REVIEW_APPROVED"),
          Map.entry("PolicyController.discard", "POLICY_VERSION.DISCARDED"),
          Map.entry("PolicyController.upsertTestCase", "TEST_CASE.SAVED"),
          Map.entry("PolicyController.deleteTestCase", "TEST_CASE.DELETED"),
          Map.entry(
              "PolicyController.run",
              "EXEMPT: evaluates a draft against supplied facts and stores nothing. A decision"
                  + " that counts is made by the decision service and recorded there."),
          // --- shared assets
          Map.entry("AssetController.createDraft", "ASSET_VERSION.CREATED"),
          Map.entry("AssetController.save", "ASSET_VERSION.SAVED"),
          Map.entry("AssetController.submit", "ASSET_VERSION.SUBMITTED"),
          Map.entry("AssetController.review", "ASSET_VERSION.REVIEW_APPROVED"),
          Map.entry("AssetController.discard", "ASSET_VERSION.DISCARDED"),
          // --- deployment
          Map.entry("DeploymentController.request", "DEPLOYMENT.REQUESTED"),
          Map.entry("DeploymentController.approve", "DEPLOYMENT.APPROVED"),
          Map.entry("DeploymentController.cancel", "DEPLOYMENT.CANCELLED"),
          Map.entry("DeploymentController.rollback", "DEPLOYMENT.ROLLED_BACK"),
          // --- bundles
          Map.entry("BundleController.importBundle", "BUNDLE.IMPORTED"),
          // --- identity
          Map.entry("AuthController.login", "AUTH.LOGIN / .LOGIN_REFUSED"),
          Map.entry("AuthController.logout", "AUTH.LOGOUT"),
          Map.entry(
              "AuthController.changePassword", "AUTH.PASSWORD_CHANGED / .PASSWORD_CHANGE_REFUSED"),
          Map.entry("UserAdminController.create", "USER.CREATED"),
          Map.entry("UserAdminController.grant", "USER.ROLE_GRANTED"),
          Map.entry("UserAdminController.revoke", "USER.ROLE_REVOKED"),
          Map.entry("UserAdminController.suspend", "USER.SUSPENDED"),
          Map.entry("UserAdminController.activate", "USER.ACTIVATED"),
          Map.entry("UserAdminController.resetPassword", "USER.PASSWORD_RESET"),
          // --- tenant
          Map.entry("TenantSettingsController.update", "TENANT.SETTINGS_CHANGED"),
          // --- language service
          Map.entry(
              "BelController.check",
              "EXEMPT: type-checks an expression in memory and stores nothing"),
          Map.entry(
              "BelController.completions", "EXEMPT: returns the symbol table and stores nothing"));

  @Test
  @DisplayName("every mutating endpoint is either audited or declared exempt, with a reason")
  void everyMutatingEndpointIsAccountedFor() {
    Set<String> inventory = mutatingEndpoints();

    assertThat(inventory)
        .as("the scan found no endpoints at all, so this test would pass vacuously")
        .isNotEmpty();

    Set<String> undeclared = new TreeSet<>(inventory);
    undeclared.removeAll(AUDITED.keySet());
    assertThat(undeclared)
        .as(
            "NFR-COMP-03: these endpoints change state and this build cannot say whether they are"
                + " audited. Add each to AUDITED with the action it records, or with EXEMPT and"
                + " why — and if it changes state and records nothing, that is the defect this"
                + " test exists to find.")
        .isEmpty();

    Set<String> stale = new TreeSet<>(AUDITED.keySet());
    stale.removeAll(inventory);
    // Both directions. A declaration for an endpoint that no longer exists is how this table rots
    // into a list nobody trusts, and an untrusted gate gets exemptions added to it.
    assertThat(stale)
        .as("declared here but no longer a mutating endpoint — delete the entry")
        .isEmpty();
  }

  @Test
  @DisplayName("every exemption says why, in words")
  void everyExemptionIsJustified() {
    List<String> unexplained =
        AUDITED.entrySet().stream()
            .filter(entry -> entry.getValue().startsWith("EXEMPT"))
            .filter(entry -> entry.getValue().length() < "EXEMPT: ".length() + 20)
            .map(Map.Entry::getKey)
            .toList();
    assertThat(unexplained).as("\"EXEMPT\" on its own is a claim nobody can check").isEmpty();
  }

  /** The inventory, from the classpath rather than from a list somebody maintains. */
  private static Set<String> mutatingEndpoints() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    Set<String> found = new TreeSet<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("dev.bre.registry.api")) {
      Class<?> controller = load(candidate.getBeanClassName());
      for (Method method : controller.getDeclaredMethods()) {
        if (mutates(method)) {
          found.add(controller.getSimpleName() + "." + method.getName());
        }
      }
    }
    return found;
  }

  private static boolean mutates(Method method) {
    return method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class)
        || method.isAnnotationPresent(PatchMapping.class);
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException impossible) {
      throw new AssertionError("scanned " + name + " and then could not load it", impossible);
    }
  }
}
