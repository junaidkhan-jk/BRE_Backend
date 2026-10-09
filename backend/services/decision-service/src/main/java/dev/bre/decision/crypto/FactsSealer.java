package dev.bre.decision.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bre.crypto.CryptoException;
import dev.bre.crypto.Envelope;
import dev.bre.crypto.FactsIntegrity;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.facts.FactSchema;
import dev.bre.facts.FieldGroupSplitter;
import dev.bre.irjson.BreJcs;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals and opens a fact snapshot: the §9.2 pipeline, in one place (M2-06).
 *
 * <p>Sealing a snapshot is five steps that must happen in this order, and getting the order wrong
 * is the kind of mistake that still passes a round-trip test:
 *
 * <ol>
 *   <li>canonicalise with BRE-JCS and compute the integrity HMAC — <b>over the whole snapshot,
 *       before the split</b>, because the HMAC has to cover what the decision was taken on, not one
 *       half of it;
 *   <li>split by {@code x-bre-group} into the non-identifying document and per-party identifying
 *       groups;
 *   <li>refuse outright if any identifying field belonged to no party (see below);
 *   <li>seal the non-identifying half under the tenant-hour TDK;
 *   <li>seal each party's group under that party's data principal's DPK.
 * </ol>
 *
 * <p><b>An unassignable identifying field is dropped, loudly.</b> The schema tags several fields
 * {@code identifying} that sit outside any party — {@code application.applicationRef}, {@code
 * households[].annualIncome}. There is no principal to key them to, so sealing them under the TDK
 * would put personal data under a key that is <em>never destroyed</em> and quietly make the
 * tenant's erasure obligation unmeetable for exactly those fields. There are only three options:
 * seal them unerasably, drop them, or refuse the decision. Sealing them is the one that cannot be
 * defended; refusing would reject a sound decision over a schema defect in the <em>storage</em>
 * path, with a credit decision as the collateral. So they are dropped, listed in {@link
 * Sealed#excludedPaths} for the caller, and logged by path. A replay therefore sees those fields as
 * missing and the rules that read them fail closed, which is the correct behaviour for data this
 * installation has chosen not to keep.
 *
 * <p>It is a specification gap either way — §9.2 does not say which principal an application-level
 * identifying field belongs to — and is recorded as an open question.
 *
 * <p><b>Opening is partial by design.</b> {@link #open} returns whatever it could decrypt and names
 * the parties it could not — an erased principal (§9.5) has no key, which is the mechanism working
 * rather than a failure. A sealer that threw on a shredded principal would make every honoured
 * erasure request break the explanation endpoint for that decision.
 */
public final class FactsSealer {

  private static final Logger log = LoggerFactory.getLogger(FactsSealer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TenantDataKeyProvider tenantKeys;
  private final PrincipalKeyService principalKeys;
  private final FieldGroupSplitter splitter;
  private final TenantSecretSource secrets;
  private final FactSchema schema;

  /**
   * Where a tenant's integrity secret comes from.
   *
   * <p>A port for the same reason {@link dev.bre.crypto.KmsPort} is: this secret is KEK-wrapped in
   * the registry and the unwrapping is installation-specific. It is separate from the TDK path
   * because it has a different lifetime — a TDK rotates hourly, this does not rotate at all unless
   * the {@code info} label changes, and binding them together would mean an hourly key change
   * invalidated every stored HMAC.
   */
  public interface TenantSecretSource {

    /**
     * Returns a tenant's integrity secret.
     *
     * @param tenantId the tenant
     * @return the unwrapped secret
     */
    byte[] integritySecret(String tenantId);
  }

  /**
   * A sealed snapshot, ready to be written.
   *
   * @param factsEnvelope the non-identifying half, under the tenant-hour TDK
   * @param factsKid the TDK that sealed it
   * @param principalEnvelopes each party's identifying fields, by {@code partyRef}, base64
   * @param factsHmac the integrity HMAC over the whole snapshot
   * @param excludedPaths identifying paths that belonged to no party and were therefore NOT stored
   */
  @SuppressWarnings("ArrayRecordComponent") // Ciphertext: no value equality is wanted, and a
  // defensive copy would double the number of places a sealed snapshot exists in the heap.
  public record Sealed(
      byte[] factsEnvelope,
      String factsKid,
      Map<String, String> principalEnvelopes,
      String factsHmac,
      List<String> excludedPaths) {

    /** Creates a sealed snapshot, copying the collections. */
    public Sealed {
      principalEnvelopes = Map.copyOf(principalEnvelopes);
      excludedPaths = List.copyOf(excludedPaths);
    }
  }

  /**
   * An opened snapshot.
   *
   * @param facts the facts, with every party whose key still exists rejoined
   * @param shreddedParties the parties whose identifying fields could not be recovered — erased
   *     principals (§9.5), which is expected and raises no alert
   * @param integrityVerified whether the recorded HMAC matched; false means the stored bytes are
   *     not the bytes the decision was taken on, and a replay must report {@code INTEGRITY}
   */
  public record Opened(JsonNode facts, List<String> shreddedParties, boolean integrityVerified) {

    /** Creates an opened snapshot, copying the list. */
    public Opened {
      shreddedParties = List.copyOf(shreddedParties);
    }
  }

  /**
   * Creates a sealer.
   *
   * @param tenantKeys the TDK provider
   * @param principalKeys the DPK service
   * @param secrets the integrity-secret source
   * @param schema the fact schema, which carries the {@code x-bre-group} tags
   */
  public FactsSealer(
      TenantDataKeyProvider tenantKeys,
      PrincipalKeyService principalKeys,
      TenantSecretSource secrets,
      FactSchema schema) {
    this.tenantKeys = tenantKeys;
    this.principalKeys = principalKeys;
    this.secrets = secrets;
    this.schema = schema;
    this.splitter = new FieldGroupSplitter(schema);
  }

  /**
   * Seals a fact snapshot.
   *
   * @param tenantId the tenant
   * @param decisionId the decision, which the AAD binds the envelopes to
   * @param facts the snapshot
   * @param at the decision instant, which selects the TDK hour — passed in so the envelope and the
   *     decision record agree about when the decision happened
   * @return the sealed halves
   */
  public Sealed seal(String tenantId, String decisionId, JsonNode facts, Instant at) {
    // Step 1, and the order matters: the HMAC covers the WHOLE snapshot. Computing it per half
    // would leave no way to verify that the two halves belong together.
    String hmac =
        FactsIntegrity.hmac(
            FactsIntegrity.factsHmacKey(secrets.integritySecret(tenantId), tenantId),
            canonical(facts));

    FieldGroupSplitter.Split split = splitter.split(facts);
    if (!split.unassignable().isEmpty()) {
      // Paths, never values (§9.6). At WARN rather than ERROR because the decision itself is
      // sound; what is unmet is the erasure obligation for these fields, and that is a schema
      // defect to fix rather than a request to reject.
      log.warn(
          "{} identifying fact path(s) belong to no party and are NOT stored: {}. §9.2 has no"
              + " principal to key them to; sealing them under the tenant key would make erasure"
              + " impossible for them.",
          split.unassignable().size(),
          split.unassignable());
    }

    TenantDataKeyProvider.DataKey tdk = tenantKeys.current(tenantId, at);
    byte[] factsEnvelope =
        Envelope.seal(
            tdk.kid(),
            tdk.key(),
            Envelope.aad(tenantId, "facts-ni", decisionId),
            bytes(split.nonIdentifying()));

    Map<String, String> principalEnvelopes = new LinkedHashMap<>();
    split
        .identifying()
        .forEach(
            (partyRef, group) -> {
              String principal = split.principalOf().get(partyRef);
              PrincipalKeyService.Key dpk = principalKeys.forWriting(tenantId, principal);
              byte[] sealed =
                  Envelope.seal(
                      dpk.kid(),
                      dpk.key(),
                      Envelope.principalAad(tenantId, principal, decisionId),
                      bytes(group));
              principalEnvelopes.put(partyRef, Base64.getEncoder().encodeToString(sealed));
            });

    return new Sealed(factsEnvelope, tdk.kid(), principalEnvelopes, hmac, split.unassignable());
  }

  /**
   * Opens a sealed snapshot, recovering what it can.
   *
   * @param tenantId the tenant
   * @param decisionId the decision the envelopes were bound to
   * @param factsEnvelope the non-identifying half
   * @param principalEnvelopes each party's identifying envelope, by {@code partyRef}, base64
   * @param recordedHmac the HMAC stored with the record
   * @return the facts, the parties that could not be recovered, and whether integrity held
   * @throws CryptoException if the non-identifying half itself cannot be opened — unlike a missing
   *     DPK, that is a genuine failure: the decision's own data is unreadable
   */
  public Opened open(
      String tenantId,
      String decisionId,
      byte[] factsEnvelope,
      Map<String, String> principalEnvelopes,
      String recordedHmac) {

    byte[] tdk = tenantKeys.forKid(Envelope.kidOf(factsEnvelope));
    JsonNode nonIdentifying =
        parse(Envelope.open(tdk, Envelope.aad(tenantId, "facts-ni", decisionId), factsEnvelope));

    Map<String, JsonNode> recovered = new LinkedHashMap<>();
    List<String> shredded = new java.util.ArrayList<>();
    principalEnvelopes.forEach(
        (partyRef, base64) -> {
          byte[] sealed = Base64.getDecoder().decode(base64);
          // The principal is in the envelope's own kid, which is why the kid is derived from it
          // rather than random: without that, a reader holding only the bytes could not tell which
          // key to ask for, nor which AAD to reconstruct.
          String principal = principalOf(Envelope.kidOf(sealed), tenantId);
          Optional<byte[]> dpk = principalKeys.forPrincipal(tenantId, principal);
          if (dpk.isEmpty()) {
            shredded.add(partyRef);
            return;
          }
          recovered.put(
              partyRef,
              parse(
                  Envelope.open(
                      dpk.get(), Envelope.principalAad(tenantId, principal, decisionId), sealed)));
        });

    JsonNode facts = splitter.rejoin(nonIdentifying, recovered);

    // Verified against the REJOINED document, because that is what the HMAC was computed over. It
    // therefore cannot hold when a principal has been shredded — the snapshot genuinely is no
    // longer what was decided on — so that case is reported as SHREDDED by the caller rather than
    // as a tampering alarm. Checked anyway: when nothing is shredded, a mismatch is real.
    boolean verified =
        FactsIntegrity.matches(
            FactsIntegrity.factsHmacKey(secrets.integritySecret(tenantId), tenantId),
            canonical(facts),
            recordedHmac);

    return new Opened(facts, shredded, verified);
  }

  /** The fact schema snapshots are sealed against. */
  public FactSchema schema() {
    return schema;
  }

  // ---------------------------------------------------------------------------------------------

  private static String principalOf(String dpkKid, String tenantId) {
    // dpk/<tenantId>/<principalRef>. The principal may itself contain slashes in principle, so the
    // prefix is stripped by length rather than by splitting on the separator.
    String prefix = "dpk/" + tenantId + "/";
    if (!dpkKid.startsWith(prefix)) {
      throw new CryptoException(
          CryptoException.Reason.UNKNOWN_KEY,
          "a principal envelope names " + dpkKid + ", which is not a DPK of tenant " + tenantId);
    }
    return dpkKid.substring(prefix.length());
  }

  private byte[] canonical(JsonNode facts) {
    // BRE-JCS's facts profile, which normalises schema-typed decimals so 212500.00 and 212500
    // hash alike (§6.3.3 rule 3). Without that, a caller who sends a trailing zero gets a
    // different HMAC for the same facts and every replay of their decisions reports tampering —
    // which is why the SCHEMA is what decides which paths are decimals, not the shape of the value.
    return BreJcs.canonicalizeFacts(facts, schema::isDecimal).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(JsonNode node) {
    return node.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static JsonNode parse(byte[] json) {
    try {
      return JSON.readTree(json);
    } catch (java.io.IOException e) {
      // Not possible from our own seal, so this means the plaintext is not what we wrote.
      throw new CryptoException(
          CryptoException.Reason.MALFORMED_ENVELOPE, "an opened envelope did not contain JSON", e);
    }
  }
}
