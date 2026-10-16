package dev.bre.decision.crypto;

import dev.bre.crypto.FactsIntegrity;
import dev.bre.crypto.InMemoryKeyStores;
import dev.bre.crypto.KmsPort;
import dev.bre.crypto.LocalDevKms;
import dev.bre.crypto.PrincipalKeyService;
import dev.bre.crypto.TenantDataKeyProvider;
import dev.bre.facts.LendingFacts;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires fact-snapshot encryption (LLD §9.2, M2-06).
 *
 * <p><b>The default is a development KMS, and it says so on startup.</b> There is no safe default
 * here: a real deployment needs a KMS this code does not know about, and the alternative to
 * defaulting is refusing to start — which would mean nobody could run the service locally without
 * standing up a key service first. So the dev adapter is the default and the log line is loud. Each
 * bean is {@code @ConditionalOnMissingBean}, so an installation supplies its own {@link KmsPort}
 * and everything above it is unchanged.
 *
 * <p><b>Neither key store is durable by default either</b>, and the consequence is specific: a
 * restarted pod cannot open the snapshots the previous one sealed. That is correct local behaviour
 * rather than a bug — the keys were in memory and are gone — and it is why {@code
 * bre.decision-log.store-facts} should stay off locally unless replay is being worked on. The
 * durable stores are a table on the decision database (TDKs) and a separate key shard (DPKs, §9.2)
 * with their own database roles, and they arrive with the registry (M3).
 */
@Configuration
public class CryptoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CryptoConfiguration.class);

  /**
   * The key-management port.
   *
   * @return an in-memory development KMS, unless the installation supplies its own
   */
  @Bean
  @ConditionalOnMissingBean(KmsPort.class)
  public KmsPort kmsPort() {
    log.warn(
        "USING THE DEVELOPMENT KMS. Key-encryption keys are held in this process's memory, so they"
            + " protect nothing and are lost on restart. Supply a KmsPort bean backed by a real KMS"
            + " or HSM before this service holds real applicants (§9.2, §9.6).");
    return new LocalDevKms();
  }

  /**
   * The tenant data key provider.
   *
   * <p><b>On PostgreSQL when there is one, and in memory only when there is not.</b> Until M4-04
   * this was always in memory, including in a deployed service — so the keys lived in the heap of
   * the pod that made them, a restart lost every one, and every sealed fact row became permanently
   * unreadable. Not an outage: a silent, irreversible loss found the first time somebody replayed a
   * decision.
   *
   * @param kms the key-management port
   * @param dataSource the decision-log database, when one is configured
   * @return the provider
   */
  @Bean
  @ConditionalOnMissingBean(TenantDataKeyProvider.class)
  public TenantDataKeyProvider tenantDataKeyProvider(
      KmsPort kms,
      org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSource) {
    javax.sql.DataSource configured = dataSource.getIfAvailable();
    if (configured == null) {
      warnInMemoryKeys("tenant data keys");
      return new TenantDataKeyProvider(kms, InMemoryKeyStores.tenantKeys());
    }
    return new TenantDataKeyProvider(
        kms, JdbcKeyStores.tenantKeys(new org.springframework.jdbc.core.JdbcTemplate(configured)));
  }

  /**
   * The data principal key service, whose keys are the erasure mechanism (§9.5).
   *
   * @param kms the key-management port
   * @param dataSource the decision-log database, when one is configured
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean(PrincipalKeyService.class)
  public PrincipalKeyService principalKeyService(
      KmsPort kms,
      org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSource) {
    javax.sql.DataSource configured = dataSource.getIfAvailable();
    if (configured == null) {
      warnInMemoryKeys("data principal keys");
      return new PrincipalKeyService(kms, InMemoryKeyStores.principalKeys());
    }
    return new PrincipalKeyService(
        kms,
        JdbcKeyStores.principalKeys(new org.springframework.jdbc.core.JdbcTemplate(configured)));
  }

  private static void warnInMemoryKeys(String what) {
    // WARN rather than a quiet INFO. With no database this is the only possible behaviour and is
    // right for local development — but it is also exactly the state that silently destroys data
    // if it reaches a deployment, so it says so in the words an operator needs.
    log.warn(
        "{} are held IN MEMORY: a restart destroys them, and every fact snapshot sealed under them"
            + " becomes permanently unreadable. Fine for local development; configure"
            + " spring.datasource.* before this service seals anything you need to read again.",
        what);
  }

  /**
   * The integrity-secret source.
   *
   * <p>Derived from one configured root secret per installation rather than held per tenant,
   * because the per-tenant secrets live in the registry (§9.2) and the registry does not exist yet.
   * The derivation is HKDF with the tenant as salt, so two tenants never share a key even though
   * they share a root — which is the property that matters, and the one a naive "use the configured
   * secret directly" would lose.
   *
   * @param rootSecret the configured root secret, base64; a generated one is used when absent
   * @return the source
   */
  @Bean
  @ConditionalOnMissingBean(FactsSealer.TenantSecretSource.class)
  public FactsSealer.TenantSecretSource tenantSecretSource(
      @Value("${bre.crypto.integrity-root-secret:}") String rootSecret) {

    byte[] root;
    if (rootSecret == null || rootSecret.isBlank()) {
      // Generated, not a fixed placeholder. A hard-coded default secret is worse than a random
      // one: it is the same on every installation, so an HMAC computed anywhere verifies
      // everywhere. Random means stored HMACs do not survive a restart, which is visible.
      root = dev.bre.crypto.Envelope.newDataKey();
      log.warn(
          "bre.crypto.integrity-root-secret is not set: a RANDOM secret was generated for this"
              + " process. Fact HMACs written now will not verify after a restart. Set it to a"
              + " base64 secret from your KMS.");
    } else {
      root = Base64.getDecoder().decode(rootSecret.trim());
    }
    return tenantId ->
        FactsIntegrity.hkdf(
            root, tenantId.getBytes(StandardCharsets.UTF_8), "bre/tenant-secret/1", 32);
  }

  /**
   * The sealer the decision path and the replay path share.
   *
   * @param tenantKeys the TDK provider
   * @param principalKeys the DPK service
   * @param secrets the integrity-secret source
   * @return the sealer
   */
  @Bean
  public FactsSealer factsSealer(
      TenantDataKeyProvider tenantKeys,
      PrincipalKeyService principalKeys,
      FactsSealer.TenantSecretSource secrets) {
    return new FactsSealer(tenantKeys, principalKeys, secrets, LendingFacts.schema());
  }
}
