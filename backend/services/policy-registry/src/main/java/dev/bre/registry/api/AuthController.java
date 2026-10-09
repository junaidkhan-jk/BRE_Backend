package dev.bre.registry.api;

import dev.bre.registry.auth.AuthService;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign in, sign out, who am I, change my password. */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

  private final AuthService authService;

  /**
   * Creates the controller.
   *
   * @param authService the login path
   */
  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /**
   * A login request.
   *
   * @param email the email
   * @param password the password
   * @param organisation the tenant, needed only when one email exists in more than one
   */
  public record LoginRequest(
      @NotBlank String email, @NotBlank String password, String organisation) {}

  /**
   * A login response.
   *
   * @param token the bearer token to send as {@code Authorization: Bearer <token>}
   * @param expiresAt when it stops working
   * @param subject the account identifier, which every audit entry will record
   * @param displayName the person's name
   * @param tenantId the organisation
   * @param roles the granted roles
   * @param capabilities everything those roles allow, so a UI can hide what the caller cannot do
   * @param mustChangePassword whether the next action has to be a password change
   * @param canOverrideSegregationOfDuties whether this caller may author and approve the same
   *     version — true only for an administrator in a tenant that still permits it, and every use
   *     is audited
   */
  public record LoginResponse(
      String token,
      Instant expiresAt,
      String subject,
      String displayName,
      String tenantId,
      Set<Role> roles,
      Set<Role.Capability> capabilities,
      boolean mustChangePassword,
      boolean canOverrideSegregationOfDuties) {}

  /**
   * Signs in.
   *
   * <p>Returns 401 with one message for every failure — wrong password, unknown email, suspended,
   * locked. Distinguishing them would make this endpoint an account-enumeration oracle over a
   * lender's staff directory. The audit log records which case it was.
   *
   * @param request the credentials
   * @return the session
   */
  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    AuthService.Session session =
        authService.login(
            request.email(), request.password().toCharArray(), request.organisation());
    return response(
        session.token(), session.expiresAt(), session.principal(), session.mustChangePassword());
  }

  /**
   * Signs out, revoking this session.
   *
   * @param request the request
   * @param authorization the bearer header
   * @return 204
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      HttpServletRequest request, @RequestHeader("Authorization") String authorization) {
    authService.logout(
        authorization.substring("Bearer ".length()).trim(), Authorisation.of(request));
    return ResponseEntity.noContent().build();
  }

  /**
   * Who the caller is and what they may do.
   *
   * <p>A UI calls this on load to decide which screens to show. It is <b>not</b> how authorisation
   * is enforced — every endpoint checks its own capability — because a client that decides what it
   * may do is a client an attacker can simply modify.
   *
   * @param request the request
   * @return the principal
   */
  @GetMapping("/me")
  public LoginResponse me(HttpServletRequest request) {
    return response(null, null, Authorisation.of(request), false);
  }

  /**
   * A password change request.
   *
   * @param currentPassword the current password
   * @param newPassword the replacement, at least 12 characters
   */
  public record PasswordChangeRequest(
      @NotBlank String currentPassword, @NotBlank String newPassword) {}

  /**
   * Changes the caller's own password, revoking their other sessions.
   *
   * @param request the request
   * @param body the passwords
   * @return 204
   */
  @PostMapping("/password")
  public ResponseEntity<Void> changePassword(
      HttpServletRequest request, @Valid @RequestBody PasswordChangeRequest body) {
    authService.changeOwnPassword(
        Authorisation.of(request),
        body.currentPassword().toCharArray(),
        body.newPassword().toCharArray());
    return ResponseEntity.noContent().build();
  }

  private static LoginResponse response(
      String token, Instant expiresAt, Principal principal, boolean mustChangePassword) {
    Set<Role.Capability> capabilities =
        principal.roles().stream()
            .flatMap(role -> role.capabilities().stream())
            .collect(
                java.util.stream.Collectors.toCollection(
                    () -> java.util.EnumSet.noneOf(Role.Capability.class)));
    return new LoginResponse(
        token,
        expiresAt,
        principal.subject(),
        principal.displayName(),
        principal.tenantId(),
        principal.roles(),
        capabilities,
        mustChangePassword,
        principal.mayOverrideSegregationOfDuties());
  }

  /** The roles this build understands, so an admin screen does not hard-code the list. */
  @GetMapping("/roles")
  public List<RoleDescription> roles() {
    return java.util.Arrays.stream(Role.values())
        .map(role -> new RoleDescription(role, role.capabilities()))
        .toList();
  }

  /**
   * A role and what it allows.
   *
   * @param role the role
   * @param capabilities what it grants
   */
  public record RoleDescription(Role role, Set<Role.Capability> capabilities) {}
}
