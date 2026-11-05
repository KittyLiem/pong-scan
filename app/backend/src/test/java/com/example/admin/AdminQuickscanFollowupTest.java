package com.example.admin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.example.support.Api;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Beheerders zien quickscan-accounts met toestemming en mailen ieder account hooguit één keer handmatig. */
@QuarkusTest
class AdminQuickscanFollowupTest {
  private static final String PASSWORD = "geheim-wachtwoord";
  private static final String LIST = "/v1/admin/quickscan-accounts";
  private static final String MARK = "/v1/admin/quickscan-accounts/{memberId}/contacted";

  @Inject AgroalDataSource dataSource;

  @Test
  void onlyAdminsHaveAccess() {
    String member = Api.newMemberToken();
    Api.as(member).when().get(LIST).then().statusCode(403);
    Api.as(member).when().post(MARK, UUID.randomUUID()).then().statusCode(403);
    Api.as(member).when().get("/v1/me").then().statusCode(200).body("features.quickscanFollowup", equalTo(false));
    Api.as(Api.adminToken()).when().get("/v1/me").then().statusCode(200)
        .body("features.quickscanFollowup", equalTo(true));
  }

  @Test
  void pendingListsOnlyConsentingQuickscanAccountsWithAQuickscan() {
    String admin = Api.adminToken();
    QuickscanAccount consenting = quickscanAccount(true, true);
    QuickscanAccount withoutConsent = quickscanAccount(false, true);
    QuickscanAccount withoutScan = quickscanAccount(true, false);

    JsonPath row = find(admin, "pending", consenting.id()).orElseThrow();
    assertThat(row.getString("accountIdentifier")).isEqualTo(consenting.email());
    assertThat(row.getString("organizationName")).startsWith("Testorganisatie");
    assertThat(row.getString("quickscan.scanId")).isNotNull();
    assertThat(row.getString("quickscan.status")).isNotNull();
    assertThat(row.getBoolean("marketingConsent")).isTrue();
    assertThat(row.getString("marketingConsentVersion")).isEqualTo("2026-11");
    assertThat(row.getString("contact")).isNull();

    assertThat(find(admin, "pending", withoutConsent.id())).isEmpty();
    assertThat(find(admin, "pending", withoutScan.id())).isEmpty();
    assertThat(find(admin, "contacted", consenting.id())).isEmpty();
  }

  @Test
  void accountCanBeMarkedAsContactedOnlyOnce() throws SQLException {
    String admin = Api.adminToken();
    QuickscanAccount account = quickscanAccount(true, true);

    Api.as(admin).when().post(MARK, account.id()).then().statusCode(200)
        .body("memberId", equalTo(account.id()))
        .body("contact.channel", equalTo("manual"))
        .body("contact.status", equalTo("sent"))
        .body("contact.contactedBy", equalTo(Api.ADMIN_USER))
        .body("contact.contactedAt", notNullValue());
    Api.as(admin).when().post(MARK, account.id()).then().statusCode(409);

    assertThat(find(admin, "pending", account.id())).isEmpty();
    JsonPath contacted = find(admin, "contacted", account.id()).orElseThrow();
    assertThat(contacted.getString("contact.channel")).isEqualTo("manual");

    assertThat(single("select count(*) from member_outreach where member_id = ? and campaign = 'quickscan-followup'",
        account.id())).isEqualTo("1");
    assertThat(single("select consent_version from member_outreach where member_id = ?", account.id()))
        .isEqualTo("2026-11");
    assertThat(single("select count(*) from audit_events where resource_id = ? "
        + "and action = 'admin.quickscan.followup.contacted' and outcome = 'success'", account.id())).isEqualTo("1");
  }

  @Test
  void accountsWithoutValidConsentCannotBeMarked() throws SQLException {
    String admin = Api.adminToken();
    Api.as(admin).when().post(MARK, quickscanAccount(false, true).id()).then().statusCode(422);

    QuickscanAccount withdrawn = quickscanAccount(true, true);
    execute("update account_members set marketing_consent_withdrawn_at = current_timestamp where id = ?",
        withdrawn.id());
    assertThat(find(admin, "pending", withdrawn.id())).isEmpty();
    Api.as(admin).when().post(MARK, withdrawn.id()).then().statusCode(422);
    assertThat(single("select count(*) from member_outreach where member_id = ?", withdrawn.id())).isEqualTo("0");
  }

  @Test
  void onlyQuickscanAccountsWithAQuickscanCanBeMarked() {
    String admin = Api.adminToken();
    Api.as(admin).when().post(MARK, quickscanAccount(true, false).id()).then().statusCode(404);
    String fullMember = Api.as(Api.newMemberToken()).when().get("/v1/me").then().extract().path("id");
    Api.as(admin).when().post(MARK, fullMember).then().statusCode(404);
    Api.as(admin).when().post(MARK, UUID.randomUUID()).then().statusCode(404);
  }

  @Test
  void declinedAccountsAreCounted() throws SQLException {
    String admin = Api.adminToken();
    long before = declinedCount(admin);

    quickscanAccount(false, true);
    QuickscanAccount withdrawn = quickscanAccount(true, true);
    execute("update account_members set marketing_consent_withdrawn_at = current_timestamp where id = ?",
        withdrawn.id());
    quickscanAccount(true, true);   // toestemming: telt niet mee
    quickscanAccount(false, false); // geen quickscan: telt niet mee

    assertThat(declinedCount(admin)).isEqualTo(before + 2);
    Api.as(admin).queryParam("status", "contacted").when().get(LIST).then().statusCode(200)
        .body("declinedCount", equalTo((int) (before + 2)));
  }

  private static long declinedCount(String admin) {
    return Api.as(admin).when().get(LIST).then().statusCode(200).extract().jsonPath().getLong("declinedCount");
  }

  @Test
  void unknownStatusIsRejected() {
    Api.as(Api.adminToken()).queryParam("status", "alles").when().get(LIST).then().statusCode(400);
  }

  private record QuickscanAccount(String id, String email) {}

  private static QuickscanAccount quickscanAccount(boolean marketingConsent, boolean withQuickscan) {
    String email = Api.uniqueEmail("opvolging");
    String id = given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", email, "credential", PASSWORD, "entry", "quickscan",
            "acceptedTerms", true, "marketingConsent", marketingConsent))
        .when().post("/v1/auth/registrations").then().statusCode(201).extract().path("id");
    if (withQuickscan) {
      String token = Api.login(email, PASSWORD).getString("accessToken");
      Api.createOrganization(token);
      Api.as(token).when().put("/v1/quickscan").then().statusCode(201);
    }
    return new QuickscanAccount(id, email);
  }

  /** Zoekt het account op alle pagina's van de lijst; de testdatabase wordt met andere tests gedeeld. */
  private static Optional<JsonPath> find(String admin, String status, String memberId) {
    String cursor = null;
    do {
      var request = Api.as(admin).queryParam("status", status).queryParam("pageSize", 100);
      if (cursor != null) request = request.queryParam("cursor", cursor);
      JsonPath page = request.when().get(LIST).then().statusCode(200).extract().jsonPath();
      int size = page.getList("items").size();
      for (int index = 0; index < size; index++) {
        if (memberId.equals(page.getString("items[" + index + "].memberId"))) {
          return Optional.of(new JsonPath(page.prettify()).setRootPath("items[" + index + "]"));
        }
      }
      cursor = page.getString("nextCursor");
    } while (cursor != null);
    return Optional.empty();
  }

  private String single(String sql, String id) throws SQLException {
    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(id));
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  private void execute(String sql, String id) throws SQLException {
    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(id));
      statement.executeUpdate();
    }
  }
}
