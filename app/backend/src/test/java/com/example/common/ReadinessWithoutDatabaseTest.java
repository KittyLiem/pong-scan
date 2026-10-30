package com.example.common;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

@QuarkusTest
@TestProfile(ReadinessWithoutDatabaseTest.PostgresProfile.class)
class ReadinessWithoutDatabaseTest {
  PostgreSQLContainer database;

  public static class PostgresProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.db-kind", "postgresql",
          "quarkus.datasource.jdbc.url", "${readiness.jdbc.url}",
          "quarkus.datasource.username", "readiness",
          "quarkus.datasource.password", "readiness-test-password",
          "quarkus.datasource.jdbc.acquisition-timeout", "2S",
          "quarkus.datasource.jdbc.validation-query-timeout", "2S",
          "app.flyway.locations", "db/migration,db/demo");
    }

    @Override
    public List<TestResourceEntry> testResources() {
      return List.of(new TestResourceEntry(PostgresResource.class));
    }
  }

  public static class PostgresResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer database;

    @Override
    public Map<String, String> start() {
      database = new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("readiness").withUsername("readiness").withPassword("readiness-test-password")
          .withUrlParam("connectTimeout", "2").withUrlParam("socketTimeout", "2");
      database.start();
      return Map.of("readiness.jdbc.url", database.getJdbcUrl());
    }

    @Override
    public void inject(TestInjector testInjector) {
      testInjector.injectIntoFields(database, new TestInjector.MatchesType(PostgreSQLContainer.class));
    }

    @Override
    public void stop() {
      if (database != null) database.stop();
    }
  }

  @Test
  void readinessTurnsDownWhenPostgresStops() throws InterruptedException {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    database.stop();

    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Response readiness;
    do {
      readiness = given().when().get("/q/health/ready");
      if (readiness.statusCode() == 503 && "DOWN".equals(readiness.jsonPath().getString("status"))) break;
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);

    assertThat(readiness.statusCode()).as("Readiness na database-uitval: %s", readiness.asString()).isEqualTo(503);
    assertThat(readiness.jsonPath().getString("status")).isEqualTo("DOWN");
    assertThat(readiness.jsonPath().getList("checks.findAll { it.status == 'DOWN' }.name", String.class))
        .anySatisfy(name -> assertThat(name).containsIgnoringCase("database"));
  }
}
