package com.example.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/** Laat de applicatie in productie (LaunchMode.NORMAL) direct stoppen bij ontbrekende of zwakke geheimen. */
@ApplicationScoped
public class ProductionConfigCheck {
  private static final Logger LOG = Logger.getLogger(ProductionConfigCheck.class);

  private static volatile boolean verified;

  void onStart(@Observes StartupEvent event) {
    verifyProduction();
  }

  /**
   * Wordt ook vóór de Flyway-migraties aangeroepen (zie FlywayLocationCustomizer), zodat er bij een onveilige
   * configuratie niets wordt gemigreerd.
   */
  public static synchronized void verifyProduction() {
    if (verified || LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Config config = ConfigProvider.getConfig();
    boolean allowInsecure = optional(config, "app.allow-insecure-local-defaults")
        .map(value -> Boolean.parseBoolean(value.trim())).orElse(false);
    List<String> locations = optional(config, "app.flyway.locations")
        .map(value -> java.util.Arrays.asList(value.split(","))).orElse(List.of("db/migration"));
    if (allowInsecure) {
      LOG.warn("!!! " + ProductionConfigValidator.INSECURE_WARNING + " (APP_ALLOW_INSECURE_LOCAL_DEFAULTS=true) !!!");
    }
    List<String> problems = ProductionConfigValidator.validate(
        optional(config, "account.token.secret"),
        optional(config, "quarkus.datasource.jdbc.url"),
        optional(config, "quarkus.datasource.username"),
        optional(config, "quarkus.datasource.password"),
        locations, allowInsecure);
    if (!problems.isEmpty()) {
      problems.forEach(problem -> LOG.error("Onveilige productieconfiguratie: " + problem));
      throw new IllegalStateException("Onveilige productieconfiguratie: " + String.join(" ", problems));
    }
    verified = true;
  }

  private static java.util.Optional<String> optional(Config config, String name) {
    try {
      return config.getOptionalValue(name, String.class);
    } catch (RuntimeException exception) {
      // Bijvoorbeeld een niet-oplosbare ${ENV}-expressie: behandelen als ontbrekend.
      return java.util.Optional.empty();
    }
  }
}
