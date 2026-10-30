package com.example.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure validatie van productie-instellingen; levert een lijst met (Nederlandstalige) bezwaren op. */
public final class ProductionConfigValidator {
  public static final String OLD_DEV_SECRET = "replace-this-development-token-secret-with-at-least-32-characters";
  public static final String INSECURE_WARNING =
      "Onveilige lokale standaardwaarden actief — niet gebruiken voor gedeelde omgevingen.";

  /** Bekende (meegeleverde) geheimen: dev/test-waarden en de standaardwaarden uit docker-compose en .env.example. */
  static final List<String> KNOWN_WEAK_SECRETS = List.of(
      OLD_DEV_SECRET,
      "dev-only-token-secret-that-is-at-least-32-characters-long",
      "test-only-token-secret-that-is-long-enough-for-hmac",
      "local-only-token-secret-change-me-for-any-shared-environment",
      "vervang-door-een-willekeurig-geheim-van-minimaal-32-tekens",
      "pong-local-only-database-password",
      "vervang-door-een-sterk-wachtwoord");
  static final List<String> KNOWN_WEAK_DB_PASSWORDS = List.of(
      "app", "password", "postgres", "sa", "pong",
      "pong-local-only-database-password",
      "vervang-door-een-sterk-wachtwoord",
      "local-only-token-secret-change-me-for-any-shared-environment",
      "vervang-door-een-willekeurig-geheim-van-minimaal-32-tekens");
  public static final int MIN_SECRET_LENGTH = 32;

  private ProductionConfigValidator() {}

  public static List<String> validate(Optional<String> tokenSecret, Optional<String> dbUrl,
      Optional<String> dbUsername, Optional<String> dbPassword) {
    return validate(tokenSecret, dbUrl, dbUsername, dbPassword, List.of("db/migration"), false);
  }

  /**
   * @param flywayLocations actieve migratielocaties (app.flyway.locations)
   * @param allowInsecureLocalDefaults alleen voor lokale docker-compose: staat bekende standaardwaarden en demodata toe
   */
  public static List<String> validate(Optional<String> tokenSecret, Optional<String> dbUrl,
      Optional<String> dbUsername, Optional<String> dbPassword, List<String> flywayLocations,
      boolean allowInsecureLocalDefaults) {
    List<String> problems = new ArrayList<>();
    String secret = tokenSecret.map(String::trim).orElse("");
    if (secret.isEmpty()) {
      problems.add("ACCOUNT_TOKEN_SECRET (account.token.secret) ontbreekt.");
    } else if (secret.length() < MIN_SECRET_LENGTH) {
      problems.add("ACCOUNT_TOKEN_SECRET moet minimaal " + MIN_SECRET_LENGTH + " tekens bevatten.");
    } else if (!allowInsecureLocalDefaults && KNOWN_WEAK_SECRETS.contains(secret)) {
      problems.add("ACCOUNT_TOKEN_SECRET gebruikt een bekende standaard- of ontwikkelwaarde; kies een eigen geheim.");
    }
    if (dbUrl.map(String::isBlank).orElse(true)) {
      problems.add("APP_DB_URL (quarkus.datasource.jdbc.url) ontbreekt.");
    }
    if (dbUsername.map(String::isBlank).orElse(true)) {
      problems.add("APP_DB_USERNAME (quarkus.datasource.username) ontbreekt.");
    }
    String password = dbPassword.orElse("");
    if (password.isBlank()) {
      problems.add("APP_DB_PASSWORD (quarkus.datasource.password) ontbreekt.");
    } else if (!allowInsecureLocalDefaults && KNOWN_WEAK_DB_PASSWORDS.contains(password.trim())) {
      problems.add("APP_DB_PASSWORD gebruikt een bekende standaard- of ontwikkelwaarde; kies een eigen wachtwoord.");
    } else if (!allowInsecureLocalDefaults && password.length() < 12) {
      problems.add("APP_DB_PASSWORD moet minimaal 12 tekens bevatten.");
    }
    if (!allowInsecureLocalDefaults && (flywayLocations == null || flywayLocations.isEmpty()
        || flywayLocations.stream().anyMatch(location -> !List.of("db/migration", "classpath:db/migration")
            .contains(location.trim())))) {
      problems.add("Alleen db/migration (of classpath:db/migration) is toegestaan in productie; "
          + "demodata (db/demo) en andere Flyway-locaties zijn verboden.");
    }
    return problems;
  }
}
