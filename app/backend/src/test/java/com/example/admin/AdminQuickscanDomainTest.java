package com.example.admin;

import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** De beheerder kiest per kennisgebied of het in nieuwe quickscans zit. */
@QuarkusTest
class AdminQuickscanDomainTest {

  @Test
  void inQuickscanIsStoredAndKeptWhenOmitted() {
    String admin = Api.adminToken();
    var created = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Testdomein quickscan", "order", 960, "active", false, "version", 0, "inQuickscan", true))
        .when().post("/v1/admin/domains")
        .then().statusCode(201).body("inQuickscan", equalTo(true)).extract().jsonPath();
    String id = created.getString("id");

    var kept = Api.as(admin)
        .body(Map.of("name", "Testdomein quickscan", "order", 960, "active", false, "version", created.getLong("version")))
        .when().patch("/v1/admin/domains/{id}", id)
        .then().statusCode(200).body("inQuickscan", equalTo(true)).extract().jsonPath();

    Api.as(admin)
        .body(Map.of("name", "Testdomein quickscan", "order", 960, "active", false, "version", kept.getLong("version"),
            "inQuickscan", false))
        .when().patch("/v1/admin/domains/{id}", id)
        .then().statusCode(200).body("inQuickscan", equalTo(false));
  }

  @Test
  void newDomainIsNotInQuickscanByDefault() {
    Api.as(Api.adminToken()).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Testdomein zonder keuze", "order", 961, "active", false, "version", 0))
        .when().post("/v1/admin/domains")
        .then().statusCode(201).body("inQuickscan", equalTo(false));
  }
}
