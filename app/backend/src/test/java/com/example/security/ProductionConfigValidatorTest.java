package com.example.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductionConfigValidatorTest {
  private static final Optional<String> URL = Optional.of("jdbc:postgresql://db:5432/pong");
  private static final Optional<String> USER = Optional.of("pong");
  private static final Optional<String> STRONG_PASSWORD = Optional.of("een-sterk-databasewachtwoord");
  private static final Optional<String> STRONG_SECRET = Optional.of("x7Gq2LmP9sVb4NcR8tWy1ZaK5dHf3JeU");

  @Test
  void acceptsStrongConfiguration() {
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, STRONG_PASSWORD)).isEmpty();
  }

  @Test
  void rejectsMissingSecret() {
    assertThat(ProductionConfigValidator.validate(Optional.empty(), URL, USER, STRONG_PASSWORD))
        .singleElement().asString().contains("ACCOUNT_TOKEN_SECRET");
    assertThat(ProductionConfigValidator.validate(Optional.of("   "), URL, USER, STRONG_PASSWORD)).hasSize(1);
  }

  @Test
  void rejectsShortSecret() {
    assertThat(ProductionConfigValidator.validate(Optional.of("te-kort-geheim"), URL, USER, STRONG_PASSWORD))
        .singleElement().asString().contains("minimaal 32");
  }

  @Test
  void rejectsOldDevelopmentFallbackSecret() {
    List<String> problems = ProductionConfigValidator.validate(
        Optional.of("replace-this-development-token-secret-with-at-least-32-characters"), URL, USER, STRONG_PASSWORD);
    assertThat(problems).singleElement().asString().contains("ontwikkelwaarde");
  }

  @Test
  void rejectsMissingOrDefaultDatabaseCredentials() {
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.empty()))
        .singleElement().asString().contains("APP_DB_PASSWORD");
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.of("app")))
        .singleElement().asString().contains("ontwikkelwaarde");
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, Optional.empty(), Optional.empty(), STRONG_PASSWORD))
        .hasSize(2);
  }

  @Test
  void rejectsShippedComposeAndExampleDefaults() {
    for (String secret : List.of("local-only-token-secret-change-me-for-any-shared-environment",
        "vervang-door-een-willekeurig-geheim-van-minimaal-32-tekens")) {
      assertThat(ProductionConfigValidator.validate(Optional.of(secret), URL, USER, STRONG_PASSWORD,
          List.of("db/migration"), false)).singleElement().asString().contains("ACCOUNT_TOKEN_SECRET");
    }
    for (String password : List.of("pong-local-only-database-password", "vervang-door-een-sterk-wachtwoord")) {
      assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.of(password),
          List.of("db/migration"), false)).singleElement().asString().contains("APP_DB_PASSWORD");
    }
  }

  @Test
  void rejectsDemoDataInProduction() {
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, STRONG_PASSWORD,
        List.of("db/migration", " db/demo"), false)).singleElement().asString().contains("db/demo");
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, STRONG_PASSWORD,
        List.of("classpath:db/migration", "classpath:db/demo/"), false)).hasSize(1);
  }

  @Test
  void insecureLocalDefaultsFlagAllowsShippedValuesButNotMissingOrShort() {
    assertThat(ProductionConfigValidator.validate(
        Optional.of("local-only-token-secret-change-me-for-any-shared-environment"), URL, USER,
        Optional.of("pong-local-only-database-password"), List.of("db/migration", "db/demo"), true)).isEmpty();
    assertThat(ProductionConfigValidator.validate(Optional.of("kort"), URL, USER, Optional.empty(),
        List.of("db/migration"), true)).hasSize(2);
  }
  @Test
  void productionAllowsOnlyExactMigrationLocations() {
    for (String location : List.of("db", "classpath:db", "db/migration,db/demo", "db/../db/demo")) {
      assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, STRONG_PASSWORD,
          java.util.Arrays.asList(location.split(",")), false))
          .singleElement().asString().contains("Alleen db/migration");
    }
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, STRONG_PASSWORD,
        List.of("classpath:db/migration"), false)).isEmpty();
  }

  @Test
  void databasePasswordLengthIsEnforcedOutsideLocalMode() {
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.of("abcdefghijk")))
        .singleElement().asString().contains("minimaal 12");
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.of("abcdefghijkl"))).isEmpty();
    assertThat(ProductionConfigValidator.validate(STRONG_SECRET, URL, USER, Optional.of("kort"),
        List.of("db/migration"), true)).isEmpty();
  }
}
