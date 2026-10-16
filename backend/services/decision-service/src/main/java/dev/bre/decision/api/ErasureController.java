package dev.bre.decision.api;

import dev.bre.crypto.PrincipalKeyService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Erasing a data principal, by destroying their key (§9.5, M4-04).
 *
 * <p><b>Crypto-shredding is how a ten-year WORM retention and a right to erasure are both kept.</b>
 * A lender must retain lending decisions for years, on storage that is deliberately write-once —
 * and a data principal may require their personal data erased. Those are irreconcilable if erasure
 * means deleting rows: the rows cannot be edited, and a backup tape from last March certainly
 * cannot. So the identifying fields were never stored in a form anyone can read without a key that
 * belongs to that one person, and erasure destroys the key. Every copy everywhere — hot store,
 * archive, backup, replica — becomes unreadable at once, including copies nobody can reach.
 *
 * <p><b>The decision itself survives, and that is the point rather than a shortfall.</b> After an
 * erasure a lender can still show that an application was declined on a given date under a given
 * policy version, with its reason codes — which is what a regulator requires and what the law
 * expects to remain. What is gone is who it was about.
 *
 * <p><b>Irreversible, and nothing here pretends otherwise.</b> There is no undo, no soft delete and
 * no tombstone: a key that still exists has not been destroyed, and an erasure leaving the key
 * behind is a lie told to a data principal and to a regulator. The {@code confirm} parameter is
 * therefore required — not as ceremony, but because the obvious operator error is a typo in a
 * principal reference, which erases somebody who never asked.
 *
 * <p><b>Authorisation, stated as the weakness it currently is.</b> This service has no
 * authentication (M3-09 did the control plane; the decision API is still behind the gateway and
 * {@code X-Tenant-Id} is unauthenticated). So this endpoint is as exposed as every other one here,
 * and it is the most destructive of them. It must not be reachable from an untrusted network — that
 * is already true of this whole service and is doubly true of this route. §9.2 asks for more than
 * that: the account that destroys keys must not be the account that writes decisions, which is a
 * database grant an installation makes.
 */
@RestController
@RequestMapping("/v1/principals")
public class ErasureController {

  private static final Logger log = LoggerFactory.getLogger(ErasureController.class);

  private final PrincipalKeyService principalKeys;

  /**
   * Creates the controller.
   *
   * @param principalKeys the key service whose delete is the erasure
   */
  public ErasureController(PrincipalKeyService principalKeys) {
    this.principalKeys = principalKeys;
  }

  /**
   * Destroys a data principal's key, erasing their identifying data everywhere.
   *
   * <p>Idempotent: a second call reports {@code erased=false} and succeeds. "Never had a key" and
   * "already erased" are deliberately the same answer — distinguishing them would turn this
   * endpoint into an oracle for whether a given person was ever assessed, which is itself the
   * disclosure it exists to prevent.
   *
   * @param tenantId the tenant, from {@code X-Tenant-Id}
   * @param principalRef the data principal
   * @param confirm must be the principal reference again; the guard against a typo
   * @return what happened
   */
  @DeleteMapping("/{principalRef}/data")
  public ResponseEntity<Map<String, Object>> erase(
      @RequestHeader("X-Tenant-Id") String tenantId,
      @PathVariable String principalRef,
      @RequestParam(name = "confirm", defaultValue = "") String confirm) {

    if (!principalRef.equals(confirm)) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "erased",
                  false,
                  "detail",
                  "This destroys a key and cannot be undone. Repeat the principal reference as"
                      + " ?confirm= to proceed."));
    }

    boolean erased = principalKeys.shred(tenantId, principalRef);

    // Logged because an erasure is a governance event somebody will be asked about later. The
    // principal reference is in the message: it is a reference, not a name or an identifier
    // document, and a log that cannot say WHICH erasure happened cannot evidence that it did.
    log.info(
        "erasure: tenant={} principal={} keyDestroyed={}. The decisions survive; what they were"
            + " about does not.",
        tenantId,
        principalRef,
        erased);

    return ResponseEntity.ok(
        Map.of(
            "erased",
            erased,
            "detail",
            erased
                ? "The key is destroyed. Every sealed field for this principal is now unreadable,"
                    + " in every copy including archives and backups. The decisions themselves"
                    + " remain, with their outcomes and reason codes."
                : "No key existed for this principal, so there is nothing readable to erase."));
  }
}
