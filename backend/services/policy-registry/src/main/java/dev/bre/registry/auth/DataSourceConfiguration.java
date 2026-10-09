package dev.bre.registry.auth;

import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wraps the application's data source so every connection carries the request's tenant.
 *
 * <p>Declared here rather than left to auto-configuration because the wrapping has to apply to the
 * <b>primary</b> data source — the one {@code JdbcTemplate} and every repository use. A wrapper
 * applied anywhere else would leave the real queries unscoped, and because row-level security fails
 * closed the symptom would be empty results rather than an error.
 *
 * <p>Flyway keeps the <em>unwrapped</em> source via its own {@code spring.flyway.url} when one is
 * configured. That is the production shape: migrations run as a role that may create schemas and
 * reassign ownership, the application runs as {@code bre_ctl_app}, which may not. Running both as
 * the same powerful role is what hides the ownership and row-level-security problems V11 records.
 */
@Configuration
public class DataSourceConfiguration {

  /**
   * The application data source, tenant-scoped.
   *
   * @param properties the configured connection settings
   * @return the wrapped source
   */
  @Bean
  @Primary
  public DataSource dataSource(DataSourceProperties properties) {
    DataSource real =
        DataSourceBuilder.create()
            .url(properties.determineUrl())
            .username(properties.determineUsername())
            .password(properties.determinePassword())
            .driverClassName(properties.determineDriverClassName())
            .build();
    return new TenantScopedDataSource(real);
  }
}
