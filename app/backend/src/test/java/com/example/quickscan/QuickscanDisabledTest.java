package com.example.quickscan;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tenant-installatie: de quickscan bestaat niet. */
@QuarkusTest
@TestProfile(QuickscanDisabledTest.Disabled.class)
class QuickscanDisabledTest {

  public static class Disabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Eigen in-memory database: hergebruik van de gedeelde H2-database na een herstart geeft H2-fouten
      // op check-constraints.
      return Map.of("app.quickscan.enabled", "false",
          "quarkus.datasource.jdbc.url", "jdbc:h2:mem:quickscan-disabled;MODE=PostgreSQL;LOCK_TIMEOUT=10000;"
              + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }
  }

  @Test
  void quickscanIsUnavailable() {
    String token = Api.newMemberToken();
    Api.as(token).when().get("/v1/me").then().statusCode(200).body("features.quickscan", equalTo(false));
    Api.as(token).when().put("/v1/quickscan").then().statusCode(404);
    given().when().get("/v1/quickscan/domains").then().statusCode(404);
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", Api.uniqueEmail("quick"), "credential", "geheim-wachtwoord",
            "entry", "quickscan"))
        .when().post("/v1/auth/registrations").then().statusCode(422).body("errors[0].field", equalTo("entry"));
  }
}
