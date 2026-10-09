package dev.bre.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The envelope format, and the four ways an attacker would try to misuse one (§9.2). */
class EnvelopeTest {

  private static final String AAD = Envelope.aad("t_acme", "facts-ni", "dec-1");
  private static final byte[] FACTS =
      "{\"application\":{\"requestedAmount\":\"150000.00\"}}".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("sealed bytes round-trip, and the ciphertext does not contain the plaintext")
  void roundTrip() {
    byte[] key = Envelope.newDataKey();
    byte[] sealed = Envelope.seal("tdk/t_acme/2026100912/1", key, AAD, FACTS);

    assertThat(Envelope.open(key, AAD, sealed)).isEqualTo(FACTS);
    assertThat(new String(sealed, StandardCharsets.UTF_8))
        .as("the one thing that would make all of this pointless")
        .doesNotContain("150000");
  }

  @Test
  @DisplayName("the kid is readable without the key, because a reader needs it before it has one")
  void kidIsReadableWithoutTheKey() {
    byte[] sealed = Envelope.seal("tdk/t_acme/2026100912/1", Envelope.newDataKey(), AAD, FACTS);
    assertThat(Envelope.kidOf(sealed)).isEqualTo("tdk/t_acme/2026100912/1");
  }

  @Test
  @DisplayName("an envelope sealed for one purpose does not open as another")
  void purposeIsBound() {
    byte[] key = Envelope.newDataKey();
    byte[] sealed = Envelope.seal("k1", key, Envelope.aad("t_acme", "facts-ni", "dec-1"), FACTS);

    assertThatThrownBy(() -> Envelope.open(key, Envelope.aad("t_acme", "results", "dec-1"), sealed))
        .isInstanceOf(CryptoException.class)
        .satisfies(
            e ->
                assertThat(((CryptoException) e).reason())
                    .isEqualTo(CryptoException.Reason.NOT_AUTHENTIC));
  }

  @Test
  @DisplayName(
      "an envelope from one decision does not open as another's, nor one tenant's as another's")
  void tenantAndDecisionAreBound() {
    byte[] key = Envelope.newDataKey();
    byte[] sealed = Envelope.seal("k1", key, Envelope.aad("t_acme", "facts-ni", "dec-1"), FACTS);

    assertThatThrownBy(
            () -> Envelope.open(key, Envelope.aad("t_acme", "facts-ni", "dec-2"), sealed))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(
            () -> Envelope.open(key, Envelope.aad("t_other", "facts-ni", "dec-1"), sealed))
        .as("D1: one tenant's facts must not be openable as another's")
        .isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("rewriting the kid to point at a key the attacker holds does not work")
  void kidIsAuthenticated() {
    // The attack this stops: an envelope's kid is in the clear, so it is editable. If it were not
    // authenticated, an attacker could point it at a key they control and the open would succeed.
    byte[] key = Envelope.newDataKey();
    byte[] sealed = Envelope.seal("kid-a", key, AAD, FACTS);

    byte[] tampered = sealed.clone();
    tampered[2] = 'X'; // "kid-a" -> "Xid-a", same length, so the layout still parses

    assertThat(Envelope.kidOf(tampered)).isEqualTo("Xid-a");
    assertThatThrownBy(() -> Envelope.open(key, AAD, tampered))
        .isInstanceOf(CryptoException.class)
        .satisfies(
            e ->
                assertThat(((CryptoException) e).reason())
                    .isEqualTo(CryptoException.Reason.NOT_AUTHENTIC));
  }

  @Test
  @DisplayName("a single flipped ciphertext bit is detected")
  void tamperingIsDetected() {
    byte[] key = Envelope.newDataKey();
    byte[] sealed = Envelope.seal("k1", key, AAD, FACTS);
    byte[] tampered = sealed.clone();
    tampered[tampered.length - 1] ^= 0x01;

    assertThatThrownBy(() -> Envelope.open(key, AAD, tampered)).isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("a different key does not open it")
  void wrongKeyDoesNotOpen() {
    byte[] sealed = Envelope.seal("k1", Envelope.newDataKey(), AAD, FACTS);
    assertThatThrownBy(() -> Envelope.open(Envelope.newDataKey(), AAD, sealed))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("there is no way to seal without AAD")
  void aadIsMandatory() {
    byte[] key = Envelope.newDataKey();
    assertThatThrownBy(() -> Envelope.seal("k1", key, null, FACTS))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("no unbound form");
    assertThatThrownBy(() -> Envelope.seal("k1", key, "  ", FACTS))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("a future format byte is refused rather than misread")
  void unknownFormatIsRefused() {
    byte[] sealed = Envelope.seal("k1", Envelope.newDataKey(), AAD, FACTS);
    sealed[0] = 0x02;
    assertThatThrownBy(() -> Envelope.kidOf(sealed))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("expected 0x01");
  }

  @Test
  @DisplayName("a truncated envelope is refused, not read past its end")
  void truncatedIsRefused() {
    byte[] sealed = Envelope.seal("k1", Envelope.newDataKey(), AAD, FACTS);
    byte[] cut = java.util.Arrays.copyOf(sealed, 5);
    assertThatThrownBy(() -> Envelope.kidOf(cut))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("truncated");
  }

  @Test
  @DisplayName("a wrong-sized key is rejected before any cipher sees it")
  void keySizeIsChecked() {
    assertThatThrownBy(() -> Envelope.seal("k1", new byte[16], AAD, FACTS))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  @DisplayName("compression happens, so the hot store's size estimate holds")
  void compressionHappens() {
    // §5.4 sizes the hot store on compressed envelopes. A facts document is extremely repetitive
    // JSON, so if this ever stops being true the storage estimate is wrong by a large factor.
    byte[] repetitive =
        ("{\"parties\":[" + "{\"a\":\"AAAAAAAAAAAAAAAAAAAA\"},".repeat(200) + "{}]}")
            .getBytes(StandardCharsets.UTF_8);
    byte[] sealed = Envelope.seal("k1", Envelope.newDataKey(), AAD, repetitive);
    assertThat(sealed.length).isLessThan(repetitive.length / 4);
  }
}
