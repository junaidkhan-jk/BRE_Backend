package dev.bre.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HKDF and the facts HMAC (§9.2).
 *
 * <p>The HKDF cases are <b>RFC 5869's own published test vectors</b>, not values this code
 * produced. A hand-written key derivation checked only against itself is a hand-written key
 * derivation that is consistently wrong, and the failure mode is silent: everything round-trips
 * because the same wrong function is used on both sides, and the keys are simply weaker than
 * believed. The RFC's vectors are the only thing that can catch that.
 */
class FactsIntegrityTest {

  private static final HexFormat HEX = HexFormat.of();

  @Test
  @DisplayName("RFC 5869 A.1 — basic SHA-256 case")
  void rfc5869CaseOne() {
    byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
    byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

    assertThat(HEX.formatHex(FactsIntegrity.hkdf(ikm, salt, info, 42)))
        .isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
  }

  @Test
  @DisplayName("RFC 5869 A.3 — zero-length salt and info")
  void rfc5869CaseThree() {
    byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    assertThat(HEX.formatHex(FactsIntegrity.hkdf(ikm, new byte[0], new byte[0], 42)))
        .isEqualTo(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
  }

  @Test
  @DisplayName("a different purpose label gives a different key from the same secret")
  void purposeSeparation() {
    byte[] secret = "tenant-secret".getBytes(StandardCharsets.UTF_8);
    assertThat(FactsIntegrity.hkdf(secret, null, "bre/facts-hmac/1", 32))
        .as("this is the entire reason for deriving rather than using the secret directly")
        .isNotEqualTo(FactsIntegrity.hkdf(secret, null, "bre/facts-hmac/2", 32));
  }

  @Test
  @DisplayName("two tenants with the same secret still get different keys")
  void tenantSeparation() {
    byte[] secret = "same".getBytes(StandardCharsets.UTF_8);
    assertThat(FactsIntegrity.factsHmacKey(secret, "t_acme"))
        .isNotEqualTo(FactsIntegrity.factsHmacKey(secret, "t_other"));
  }

  @Test
  @DisplayName("the HMAC matches the same canonical bytes and rejects altered ones")
  void hmacDetectsAlteration() {
    byte[] key = FactsIntegrity.factsHmacKey("secret".getBytes(StandardCharsets.UTF_8), "t_acme");
    byte[] canonical = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
    String hmac = FactsIntegrity.hmac(key, canonical);

    assertThat(FactsIntegrity.matches(key, canonical, hmac)).isTrue();
    assertThat(
            FactsIntegrity.matches(key, "{\"a\":1,\"b\":3}".getBytes(StandardCharsets.UTF_8), hmac))
        .as("§9.2: a mismatch makes a replay report matched=false with reason INTEGRITY")
        .isFalse();
    assertThat(FactsIntegrity.matches(key, canonical, null))
        .as("a record with no stored HMAC must not be treated as verified")
        .isFalse();
    assertThat(FactsIntegrity.matches(key, canonical, "")).isFalse();
  }

  @Test
  @DisplayName("a different key does not verify")
  void wrongKeyDoesNotVerify() {
    byte[] canonical = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] one = FactsIntegrity.factsHmacKey("a".getBytes(StandardCharsets.UTF_8), "t_acme");
    byte[] two = FactsIntegrity.factsHmacKey("b".getBytes(StandardCharsets.UTF_8), "t_acme");
    assertThat(FactsIntegrity.matches(two, canonical, FactsIntegrity.hmac(one, canonical)))
        .isFalse();
  }
}
