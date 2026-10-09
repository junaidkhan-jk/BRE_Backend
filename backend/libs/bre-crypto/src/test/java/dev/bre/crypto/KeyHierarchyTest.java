package dev.bre.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The key hierarchy: TDKs per tenant-hour, DPKs per person, and erasure (§9.2, §9.3, §9.5). */
class KeyHierarchyTest {

  private static final Instant NOON = Instant.parse("2026-10-09T12:30:00Z");
  private static final byte[] PLAINTEXT = "identifying".getBytes(StandardCharsets.UTF_8);

  private static TenantDataKeyProvider tdks(LocalDevKms kms) {
    return new TenantDataKeyProvider(kms, InMemoryKeyStores.tenantKeys());
  }

  @Test
  @DisplayName("a TDK is stable within an hour and different in the next")
  void oneKeyPerTenantHour() {
    TenantDataKeyProvider provider = tdks(new LocalDevKms());

    var noon = provider.current("t_acme", NOON);
    var sameHour = provider.current("t_acme", NOON.plusSeconds(600));
    var nextHour = provider.current("t_acme", NOON.plusSeconds(3600));

    assertThat(noon.kid()).isEqualTo(sameHour.kid()).isEqualTo("tdk/t_acme/2026100912/1");
    assertThat(noon.key()).isEqualTo(sameHour.key());
    assertThat(nextHour.kid()).isEqualTo("tdk/t_acme/2026100913/1");
    assertThat(nextHour.key())
        .as("a leaked key must expose one hour, not everything")
        .isNotEqualTo(noon.key());
  }

  @Test
  @DisplayName("two tenants never share a key")
  void tenantsAreIsolated() {
    TenantDataKeyProvider provider = tdks(new LocalDevKms());
    assertThat(provider.current("t_acme", NOON).key())
        .isNotEqualTo(provider.current("t_other", NOON).key());
  }

  @Test
  @DisplayName("concurrent writers for the same hour agree on one key")
  void concurrentWritersAgree() throws Exception {
    // The property the store's insert-or-get exists for. If two pods got different keys, one pod's
    // envelopes would be sealed under a key nothing ever stored, and that data would be lost.
    var store = InMemoryKeyStores.tenantKeys();
    LocalDevKms kms = new LocalDevKms();
    try (var pool = Executors.newFixedThreadPool(8)) {
      // Eight independent providers, as eight pods would be: separate caches, shared store.
      var futures =
          IntStream.range(0, 8)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            var key = new TenantDataKeyProvider(kms, store).current("t_acme", NOON);
                            return java.util.HexFormat.of().formatHex(key.key());
                          }))
              .toList();
      assertThat(futures.stream().map(KeyHierarchyTest::get).distinct().toList()).hasSize(1);
    }
  }

  @Test
  @DisplayName("a KEK rotation does not make last hour's data unreadable")
  void rotationKeepsOldDataReadable() {
    // The failure this guards against is silent and total: rotate, and every decision sealed
    // before the rotation becomes unopenable. Nothing notices until an audit asks for one.
    LocalDevKms kms = new LocalDevKms();
    var store = InMemoryKeyStores.tenantKeys();
    TenantDataKeyProvider provider = new TenantDataKeyProvider(kms, store);

    var before = provider.current("t_acme", NOON);
    byte[] sealed =
        Envelope.seal(
            before.kid(), before.key(), Envelope.aad("t_acme", "facts-ni", "dec-1"), PLAINTEXT);

    kms.rotate("t_acme");
    var after = provider.current("t_acme", NOON.plusSeconds(3600));
    assertThat(after.kekKid()).isNotEqualTo(before.kekKid());

    // A fresh provider, as a pod started after the rotation would be: no cache, same store.
    byte[] recovered = new TenantDataKeyProvider(kms, store).forKid(Envelope.kidOf(sealed));
    assertThat(Envelope.open(recovered, Envelope.aad("t_acme", "facts-ni", "dec-1"), sealed))
        .isEqualTo(PLAINTEXT);
  }

  @Test
  @DisplayName("eviction drops old plaintext keys and keeps them unwrappable")
  void evictionDropsPlaintextOnly() {
    LocalDevKms kms = new LocalDevKms();
    var store = InMemoryKeyStores.tenantKeys();
    TenantDataKeyProvider provider = new TenantDataKeyProvider(kms, store);

    var old = provider.current("t_acme", NOON);
    provider.current("t_acme", NOON.plusSeconds(7200));
    assertThat(provider.cachedKeys()).isEqualTo(2);

    assertThat(provider.evictOlderThan("t_acme", NOON.plusSeconds(7200))).isEqualTo(1);
    assertThat(provider.cachedKeys()).isEqualTo(1);
    assertThat(provider.forKid(old.kid()))
        .as("still in the store, so still readable — only this process's copy went")
        .isNotNull();
  }

  @Test
  @DisplayName("a TDK this installation never had is an error, not a shred")
  void unknownTdkIsAnError() {
    assertThatThrownBy(() -> tdks(new LocalDevKms()).forKid("tdk/t_acme/2020010100/1"))
        .isInstanceOf(CryptoException.class)
        .satisfies(
            e ->
                assertThat(((CryptoException) e).reason())
                    .isEqualTo(CryptoException.Reason.UNKNOWN_KEY));
  }

  @Test
  @DisplayName("the dev KMS really wraps: a key presented under the wrong KEK is refused")
  void devKmsIsNotAPassThrough() {
    // If this were a pass-through, a bug that forgot to wrap would pass every test above.
    LocalDevKms kms = new LocalDevKms();
    String acme = kms.currentKek("t_acme");
    String other = kms.currentKek("t_other");
    byte[] wrapped = kms.wrap(acme, Envelope.newDataKey());

    assertThatThrownBy(() -> kms.unwrap(other, wrapped))
        .isInstanceOf(CryptoException.class)
        .satisfies(
            e ->
                assertThat(((CryptoException) e).reason())
                    .isEqualTo(CryptoException.Reason.NOT_AUTHENTIC));
  }

  // --- DPKs and erasure (§9.5) -------------------------------------------------------------

  @Test
  @DisplayName("shredding a principal's key makes their sealed data unreadable, and nobody else's")
  void shreddingIsTargeted() {
    LocalDevKms kms = new LocalDevKms();
    PrincipalKeyService dpks = new PrincipalKeyService(kms, InMemoryKeyStores.principalKeys());

    var alice = dpks.forWriting("t_acme", "PR-alice");
    var bob = dpks.forWriting("t_acme", "PR-bob");
    String aliceAad = Envelope.principalAad("t_acme", "PR-alice", "dec-1");
    String bobAad = Envelope.principalAad("t_acme", "PR-bob", "dec-1");
    byte[] aliceSealed = Envelope.seal(alice.kid(), alice.key(), aliceAad, PLAINTEXT);
    byte[] bobSealed = Envelope.seal(bob.kid(), bob.key(), bobAad, PLAINTEXT);

    assertThat(dpks.shred("t_acme", "PR-alice")).isTrue();

    assertThat(dpks.forPrincipal("t_acme", "PR-alice"))
        .as("§9.5: an erased principal has no key, and that is an expected answer not an error")
        .isEmpty();
    assertThat(dpks.forPrincipal("t_acme", "PR-bob")).isPresent();
    assertThat(
            Envelope.open(dpks.forPrincipal("t_acme", "PR-bob").orElseThrow(), bobAad, bobSealed))
        .isEqualTo(PLAINTEXT);

    // Alice's bytes are untouched and now meaningless, which is the whole mechanism: the archive
    // object does not change, it simply stops being readable.
    assertThat(aliceSealed).isNotEmpty();
  }

  @Test
  @DisplayName("shredding clears the in-process cache, so the erasure takes effect immediately")
  void shreddingClearsTheCache() {
    // Without this, a pod that had just sealed a decision for this person would keep serving their
    // unmasked data from memory until it restarted: an erasure performed but not in effect.
    PrincipalKeyService dpks =
        new PrincipalKeyService(new LocalDevKms(), InMemoryKeyStores.principalKeys());
    dpks.forWriting("t_acme", "PR-alice");
    dpks.shred("t_acme", "PR-alice");
    assertThat(dpks.forPrincipal("t_acme", "PR-alice")).isEmpty();
  }

  @Test
  @DisplayName("two decisions for the same person share one key")
  void onePersonOneKey() {
    PrincipalKeyService dpks =
        new PrincipalKeyService(new LocalDevKms(), InMemoryKeyStores.principalKeys());
    assertThat(dpks.forWriting("t_acme", "PR-alice").key())
        .as("otherwise erasing the person would only erase some of their decisions")
        .isEqualTo(dpks.forWriting("t_acme", "PR-alice").key());
  }

  @Test
  @DisplayName("an unerased principal with no key yet reads as absent, same as an erased one")
  void absentAndErasedAreTheSameAnswer() {
    PrincipalKeyService dpks =
        new PrincipalKeyService(new LocalDevKms(), InMemoryKeyStores.principalKeys());
    assertThat(dpks.forPrincipal("t_acme", "PR-nobody")).isEqualTo(Optional.empty());
    assertThat(dpks.shred("t_acme", "PR-nobody")).isFalse();
  }

  private static <T> T get(Future<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
  }
}
