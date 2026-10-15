package dev.bre.registry.api;

import dev.bre.bundle.Bundle;
import dev.bre.registry.auth.Principal;
import dev.bre.registry.auth.Role;
import dev.bre.registry.bundle.BundleTransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moving a signed bundle between installations (§2.6, §8.6, LLD §6.5, M3-07).
 *
 * <p><b>Export is POLICY_READ; import is DEPLOYMENT_APPROVE.</b> That asymmetry is the point.
 * Exporting hands out a document anybody who can read the policy source could reconstruct more
 * easily. Importing accepts that <em>another installation's approval is binding here</em> — the
 * imported version arrives APPROVED, with a signed bundle, and nobody in this installation has read
 * its contents. That is an approval, so it needs an approver.
 *
 * <p>Served and accepted as {@code application/vnd.bre.bundle+json} rather than plain JSON, because
 * a bundle is a signed artefact and not a document to be edited: its bytes are its hash.
 */
@RestController
@RequestMapping("/v1/bundles")
public class BundleController {

  private final BundleTransferService transfer;

  /**
   * Creates the controller.
   *
   * @param transfer the export and import service
   */
  public BundleController(BundleTransferService transfer) {
    this.transfer = transfer;
  }

  /**
   * Exports a bundle exactly as it was signed.
   *
   * @param request the request
   * @param bundleHash the content address
   * @return the bundle document, or 404 when this installation does not hold it
   */
  @GetMapping(value = "/{bundleHash}", produces = Bundle.MEDIA_TYPE)
  public ResponseEntity<String> export(
      HttpServletRequest request, @PathVariable String bundleHash) {
    Principal caller = Authorisation.require(request, Role.Capability.POLICY_READ);
    return transfer
        .export(caller.tenantId(), bundleHash)
        .map(
            document ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(Bundle.MEDIA_TYPE))
                    // Named by its hash, so a file on somebody's disk still says what it is.
                    .header(
                        "Content-Disposition",
                        "attachment; filename=\"" + bundleHash.replace(':', '-') + ".json\"")
                    .body(document))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  /**
   * Imports a bundle from another installation.
   *
   * <p>Verifies the hash, verifies the ORIGIN signature against this installation's
   * trusted-exporter list, countersigns IMPORT with this installation's own key, and records the
   * version as APPROVED and imported.
   *
   * @param request the request
   * @param document the exported bundle
   * @return what the import established
   */
  @PostMapping(
      value = "/import",
      consumes = {Bundle.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
  public ResponseEntity<BundleTransferService.Imported> importBundle(
      HttpServletRequest request, @RequestBody String document) {
    Principal approver = Authorisation.require(request, Role.Capability.DEPLOY_APPROVE);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(transfer.importBundle(approver, document));
  }
}
