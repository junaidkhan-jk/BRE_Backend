package dev.bre.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The dev keystore, which is what makes a restore drill possible (§9.2). */
class DevKeystoreTest {

  private static final char[] PASSWORD = "drill".toCharArray();
  private static final byte[] DATA_KEY = "a tenant data key".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("a key wrapped before a restart unwraps after one")
  void survivesARestart(@TempDir Path directory) {
    Path file = directory.resolve("kek.jceks");

    // The process that seals.
    LocalDevKms before = new LocalDevKms();
    String kekKid = before.currentKek("t_acme");
    byte[] wrapped = before.wrap(kekKid, DATA_KEY);
    DevKeystore.write(file, PASSWORD, before.persistable());

    // A different process entirely: no shared state but the file.
    LocalDevKms after = new LocalDevKms();
    after.load(DevKeystore.read(file, PASSWORD));

    assertThat(after.unwrap(kekKid, wrapped)).isEqualTo(DATA_KEY);
  }

  @Test
  @DisplayName("without the keystore the same restart cannot unwrap — the drill's negative case")
  void losingTheKeystoreLosesTheData() {
    LocalDevKms before = new LocalDevKms();
    String kekKid = before.currentKek("t_acme");
    byte[] wrapped = before.wrap(kekKid, DATA_KEY);

    // This is the whole reason the drill restores two artefacts rather than one. A database dump
    // that captures the wrapped keys and not the KEK restores ciphertext nobody can open, and it
    // reports success while doing it.
    assertThatThrownBy(() -> new LocalDevKms().unwrap(kekKid, wrapped))
        .isInstanceOf(CryptoException.class)
        .hasMessageContaining("not held by this KMS");
  }

  @Test
  @DisplayName("the wrong password is an error, not an empty keystore")
  void aWrongPasswordFailsLoudly(@TempDir Path directory) {
    Path file = directory.resolve("kek.jceks");
    LocalDevKms kms = new LocalDevKms();
    kms.currentKek("t_acme");
    DevKeystore.write(file, PASSWORD, kms.persistable());

    // Returning no keys would be the dangerous answer: the service would start, seal new decisions
    // under fresh keys, and leave every restored row unreadable — a failed restore that looks like
    // a successful one until somebody asks for an old decision.
    assertThatThrownBy(() -> DevKeystore.read(file, "wrong".toCharArray()))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  @DisplayName("two tenants differing only in case keep separate keys")
  void aliasesAreCaseSensitive(@TempDir Path directory) {
    Path file = directory.resolve("kek.jceks");

    // JKS-family aliases are case-insensitive, so storing the key identifier as the alias would
    // make these two the same entry and one tenant would silently decrypt under the other's KEK.
    LocalDevKms kms = new LocalDevKms();
    String lower = kms.currentKek("acme");
    String upper = kms.currentKek("Acme");
    assertThat(lower).isNotEqualTo(upper);

    DevKeystore.write(file, PASSWORD, kms.persistable());
    var loaded = DevKeystore.read(file, PASSWORD);

    assertThat(loaded).containsOnlyKeys(lower, upper);
    assertThat(loaded.get(lower)).isNotEqualTo(loaded.get(upper));
  }

  @Test
  @DisplayName("a missing file is not an error, and a new KEK creates one")
  void firstRunStartsEmpty(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("nested/kek.jceks");

    assertThat(DevKeystore.read(file, PASSWORD)).isEmpty();

    LocalDevKms kms = new LocalDevKms(() -> {});
    kms.currentKek("t_acme");
    DevKeystore.write(file, PASSWORD, kms.persistable());

    assertThat(Files.exists(file)).isTrue();
    assertThat(Files.getPosixFilePermissions(file))
        .containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
  }

  @Test
  @DisplayName("the callback fires once per new KEK, so the file is written when one appears")
  void newKeysAreNotified() {
    int[] calls = new int[1];
    LocalDevKms kms = new LocalDevKms(() -> calls[0]++);

    kms.currentKek("t_acme");
    kms.currentKek("t_acme");
    assertThat(calls[0]).isEqualTo(1);

    kms.rotate("t_acme");
    assertThat(calls[0]).isEqualTo(2);

    kms.currentKek("t_other");
    assertThat(calls[0]).isEqualTo(3);
  }
}
