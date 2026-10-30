package com.example.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.scan.domain.ScanEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScanQuotaTest {
  @Test
  void totalQuotaIncludesOldScansAndLeavesOtherOrganizationsAvailable() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    seed(org, 49, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
    String key = Api.key();
    String id = Api.as(token).header("X-Idempotency-Key", key).body(input())
        .post("/v1/organizations/{id}/scans", org).then().statusCode(201).extract().path("id");
    Api.as(token).header("X-Idempotency-Key", key).body(input())
        .post("/v1/organizations/{id}/scans", org).then().statusCode(201).body("id", equalTo(id));
    assertLimited(token, org);
    assertThat(count(org)).isEqualTo(50);
    Api.createScan(token, Api.createOrganization(token), List.of());
  }

  @Test
  void dailyQuotaUsesRolling24Hours() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    seed(org, 19, OffsetDateTime.now(ZoneOffset.UTC));
    seed(org, 1, OffsetDateTime.now(ZoneOffset.UTC).minusHours(25));
    Api.createScan(token, org, List.of());
    assertLimited(token, org);
    assertThat(count(org)).isEqualTo(21);
    QuarkusTransaction.requiringNew().run(() -> ScanEntity.update("createdAt = ?1 where organizationId = ?2",
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(25), UUID.fromString(org)));
    Api.createScan(token, org, List.of());
  }

  @Test
  void revisionsCountTowardsBothQuotasAndReplayStillWorks() {
    for (boolean daily : List.of(true, false)) {
      Api.CompletedScan done = Api.completedScan(1, index -> 2);
      String org = Api.scan(done.token(), done.scanId()).getString("organizationId");
      seed(org, daily ? 18 : 48, daily ? OffsetDateTime.now(ZoneOffset.UTC)
          : OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
      String key = Api.key();
      String revision = Api.as(done.token()).header("X-Idempotency-Key", key).body(Map.of("mode", "copy"))
          .post("/v1/scans/{id}/revisions", done.scanId()).then().statusCode(201).extract().path("id");
      Api.as(done.token()).header("X-Idempotency-Key", key).body(Map.of("mode", "copy"))
          .post("/v1/scans/{id}/revisions", done.scanId()).then().statusCode(201).body("id", equalTo(revision));
      Api.as(done.token()).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "new-revision"))
          .post("/v1/scans/{id}/revisions", done.scanId()).then().statusCode(429)
          .contentType("application/problem+json").body("title", equalTo("Scanlimiet bereikt"));
      assertLimited(done.token(), org);
    }
  }

  private static Map<String, String> input() {
    return Map.of("name", "Quotumtest", "assessmentPeriod", "2026");
  }

  private static void assertLimited(String token, String org) {
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(input())
        .post("/v1/organizations/{id}/scans", org).then().statusCode(429)
        .contentType("application/problem+json").body("title", equalTo("Scanlimiet bereikt"));
  }

  private static long count(String org) {
    return QuarkusTransaction.requiringNew().call(() -> ScanEntity.count("organizationId", UUID.fromString(org)));
  }

  private static void seed(String org, int count, OffsetDateTime createdAt) {
    QuarkusTransaction.requiringNew().run(() -> {
      for (int i = 0; i < count; i++) {
        ScanEntity scan = new ScanEntity();
        scan.id = UUID.randomUUID();
        scan.organizationId = UUID.fromString(org);
        scan.name = "Bestaande scan";
        scan.assessmentPeriod = "2026";
        scan.status = ScanEntity.CONCEPT;
        scan.createdAt = createdAt;
        scan.updatedAt = createdAt;
        scan.persist();
      }
    });
  }
}
