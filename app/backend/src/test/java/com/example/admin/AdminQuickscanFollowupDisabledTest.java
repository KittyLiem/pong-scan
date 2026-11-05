package com.example.admin;

import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Zodra de automatische e-mailprocedure draait, staat de handmatige opvolging uit. */
@QuarkusTest
@TestProfile(AdminQuickscanFollowupDisabledTest.Disabled.class)
class AdminQuickscanFollowupDisabledTest {

  public static class Disabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("app.quickscan.manual-followup.enabled", "false",
          "quarkus.datasource.jdbc.url", "jdbc:h2:mem:followup-disabled;MODE=PostgreSQL;LOCK_TIMEOUT=10000;"
              + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    }
  }

  @Test
  void manualFollowupIsUnavailable() {
    String admin = Api.adminToken();
    Api.as(admin).when().get("/v1/me").then().statusCode(200)
        .body("features.quickscan", equalTo(true)).body("features.quickscanFollowup", equalTo(false));
    Api.as(admin).when().get("/v1/admin/quickscan-accounts").then().statusCode(404);
    Api.as(admin).when().post("/v1/admin/quickscan-accounts/{memberId}/contacted", UUID.randomUUID())
        .then().statusCode(404);
  }
}
