package com.example.common;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ContractAndOpsTest {

  @Test
  void correlationIdIsEchoedWhenSafeAndReplacedOtherwise() {
    given().header("X-Correlation-Id", "abc-123").when().get("/v1/me")
        .then().statusCode(401).header("X-Correlation-Id", equalTo("abc-123"));
    String replaced = given().header("X-Correlation-Id", "kwaad\"<script>").when().get("/v1/me")
        .then().statusCode(401).extract().header("X-Correlation-Id");
    assertThat(replaced).matches("^[A-Za-z0-9-]{1,64}$").isNotEqualTo("kwaad\"<script>");
    String tooLong = given().header("X-Correlation-Id", "a".repeat(65)).when().get("/v1/me")
        .then().extract().header("X-Correlation-Id");
    assertThat(tooLong).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void securityHeadersArePresent() {
    Api.as(Api.demoToken()).when().get("/v1/organizations").then().statusCode(200)
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Frame-Options", "DENY")
        .header("Referrer-Policy", "no-referrer")
        .header("Cache-Control", "no-store")
        .header("X-Correlation-Id", notNullValue());
  }

  @Test
  void malformedJsonIsProblemDetails() {
    Api.as(Api.demoToken()).header("X-Idempotency-Key", Api.key()).body("{\"name\": ")
        .when().post("/v1/organizations")
        .then().statusCode(400).contentType("application/problem+json").body("title", equalTo("Ongeldige invoer"));
  }

  @Test
  void oversizedBodyIsRejected() {
    String huge = "{\"name\":\"" + "a".repeat(1_100_000) + "\"}";
    try {
      int status = given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key()).body(huge)
          .when().post("/v1/auth/registrations").then().extract().statusCode();
      assertThat(status).isEqualTo(413);
    } catch (Exception exception) {
      // De server mag de verbinding sluiten voordat de client de hele body heeft verstuurd; ook dat is afwijzing.
      Throwable cause = exception;
      while (cause.getCause() != null && !(cause instanceof java.io.IOException)) cause = cause.getCause();
      assertThat(cause).isInstanceOf(java.io.IOException.class);
    }
  }

  @Test
  void activeModelContract() {
    Api.as(Api.demoToken()).when().get("/v1/models/active").then().statusCode(200)
        .contentType("application/json")
        .body("domains.size()", greaterThanOrEqualTo(7))
        .body("domains.name", hasItems("Datastrategie", "Datakwaliteit", "Governance", "Technologie", "Processen",
            "Vaardigheden", "Datagedreven besluitvorming"))
        .body("domains[0].questionCount", equalTo(3))
        .body("domains.id", everyItem(notNullValue()))
        .body("domains[0].order", equalTo(1));
  }

  @Test
  void organizationContractSuccessAndValidation() {
    String token = Api.newMemberToken();
    Api.as(token).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Acme", "chamberOfCommerceNumber", "12345678", "industry", "Zorg"))
        .when().post("/v1/organizations")
        .then().statusCode(201).contentType("application/json")
        .body("id", notNullValue()).body("name", equalTo("Acme")).body("chamberOfCommerceNumber", equalTo("12345678"))
        .body("industry", equalTo("Zorg")).body("status", equalTo("active")).body("version", equalTo(0));
    Api.as(token).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Acme", "chamberOfCommerceNumber", "1234"))
        .when().post("/v1/organizations")
        .then().statusCode(400).contentType("application/problem+json")
        .body("status", equalTo(400)).body("errors.field", hasItem("chamberOfCommerceNumber"))
        .body("errors.message", hasItem("moet uit precies 8 cijfers bestaan"));
  }

  @Test
  void scanContractFields() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    Api.as(token).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Contract", "assessmentPeriod", "H2 2026"))
        .when().post("/v1/organizations/{id}/scans", org)
        .then().statusCode(201).contentType("application/json")
        .header("Location", notNullValue())
        .body("id", notNullValue()).body("organizationId", equalTo(org)).body("name", equalTo("Contract"))
        .body("assessmentPeriod", equalTo("H2 2026")).body("status", equalTo("concept")).body("progress", equalTo(0))
        .body("revision", equalTo(1)).body("selectedDomainIds.size()", equalTo(0)).body("version", notNullValue())
        .body("createdAt", notNullValue()).body("updatedAt", notNullValue()).body("goals.size()", equalTo(0));
  }

  @Test
  void unknownScanIsNotFoundProblem() {
    Api.as(Api.demoToken()).when().get("/v1/scans/{id}", "00000000-0000-0000-0000-000000000000")
        .then().statusCode(404).contentType("application/problem+json")
        .body("type", equalTo("about:blank")).body("title", equalTo("Niet gevonden")).body("status", equalTo(404));
  }

  @Test
  void demoPortfolioIsAvailable() {
    Api.as(Api.demoToken()).when().get("/v1/organizations/{id}/scans", Api.DEMO_ORGANIZATION)
        .then().statusCode(200).body("items.status", hasItems("completed", "in-progress", "concept"));
  }

  @Test
  void healthReadinessIncludesDatasource() {
    given().when().get("/q/health/ready").then().statusCode(200)
        .body("status", equalTo("UP")).body("checks.name", hasItem(org.hamcrest.Matchers.containsString("atabase")));
  }
}
