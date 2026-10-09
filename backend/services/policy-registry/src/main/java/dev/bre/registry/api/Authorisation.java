package dev.bre.registry.api;

import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads the authenticated principal off a request and checks capabilities.
 *
 * <p><b>Explicit at every endpoint, not a declarative annotation.</b> Spring Security's
 * {@code @PreAuthorize} would be less code and is the conventional choice; it is not used here for
 * one reason: an annotation that is forgotten is invisible, and the thing most likely to be
 * forgotten on a control-plane service is the authorisation on the endpoint added in a hurry. A
 * call to {@link #require} is a statement in the method body that a reader sees while reading the
 * method, and its absence is a gap in a code review rather than a gap in metadata.
 *
 * <p>The filter already guarantees a principal exists — it refuses the request otherwise — so
 * {@link #of} throwing is an internal invariant failure, not a user-facing case.
 */
public final class Authorisation {

  private Authorisation() {}

  /** The caller lacks a capability. Mapped to 403. */
  public static final class ForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ForbiddenException(String message) {
      super(message);
    }
  }

  /**
   * The authenticated principal.
   *
   * @param request the request
   * @return the principal
   */
  public static Principal of(HttpServletRequest request) {
    Principal principal = (Principal) request.getAttribute(Principal.ATTRIBUTE);
    if (principal == null) {
      // AuthFilter refuses every request without one, so reaching here means the filter was
      // bypassed — a mapping outside the filter's scope, say. Failing loudly beats proceeding as
      // an anonymous caller with no roles, which would look like a permission problem.
      throw new IllegalStateException(
          "no authenticated principal on the request. AuthFilter should have refused it; this"
              + " endpoint is reachable outside the filter chain.");
    }
    return principal;
  }

  /**
   * Requires a capability.
   *
   * @param request the request
   * @param capability what is needed
   * @return the principal, so a caller can use it without a second lookup
   * @throws ForbiddenException when the principal does not hold it
   */
  public static Principal require(HttpServletRequest request, Role.Capability capability) {
    Principal principal = of(request);
    if (!principal.can(capability)) {
      // Names the capability and the roles that carry it. A 403 that says only "forbidden" turns
      // into a support ticket; one that names the missing role is something an administrator can
      // act on without asking anybody.
      throw new ForbiddenException(
          "this action needs "
              + capability
              + ", which none of your roles grants. You hold: "
              + (principal.roles().isEmpty() ? "no roles" : principal.roles())
              + ". It is granted by: "
              + rolesGranting(capability)
              + ".");
    }
    return principal;
  }

  private static String rolesGranting(Role.Capability capability) {
    return String.join(
        ", ",
        java.util.Arrays.stream(Role.values())
            .filter(role -> role.capabilities().contains(capability))
            .map(Enum::name)
            .toList());
  }
}
