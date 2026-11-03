package com.example.quickscan;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

@QuarkusTest
class QuickscanTest {
  private static final String PASSWORD = "geheim-wachtwoord";

  @Inject AgroalDataSource dataSource;

  @Test
  void registrationViaQuickscanCreatesQuickscanAccount() {
    String token = quickscanToken();
    Api.as(token).when().get("/v1/me").then().statusCode(200)
        .body("plan", equalTo("quickscan")).body("features.quickscan", equalTo(true));
    Api.as(Api.newMemberToken()).when().get("/v1/me").then().statusCode(200).body("plan", equalTo("full"));
  }

  @Test
  void registrationReplayWithDifferentEntryConflicts() {
    String key = Api.key();
    String email = Api.uniqueEmail("quick");
    register(key, Map.of("accountIdentifier", email, "credential", PASSWORD, "entry", "quickscan")).statusCode(201);
    register(key, Map.of("accountIdentifier", email, "credential", PASSWORD)).statusCode(409);
    register(Api.key(), Map.of("accountIdentifier", Api.uniqueEmail("quick"), "credential", PASSWORD,
        "entry", "volledig")).statusCode(400);
  }

  @Test
  void quickscanIsCreatedOnceAndThenReturned() {
    String token = quickscanToken();
    Api.as(token).when().put("/v1/quickscan").then().statusCode(409);

    String organizationId = Api.createOrganization(token);
    String scanId = Api.as(token).when().put("/v1/quickscan").then().statusCode(201)
        .body("kind", equalTo("quick")).body("organizationId", equalTo(organizationId))
        .body("selectedDomainIds.size()", equalTo(quickscanDomainNames().size()))
        .extract().path("id");
    Api.as(token).when().put("/v1/quickscan").then().statusCode(200).body("id", equalTo(scanId));
  }

  @Test
  void quickscanAccountCannotDoAnythingElse() {
    String token = quickscanToken();
    String organizationId = Api.createOrganization(token);
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("name", "Tweede"))
        .when().post("/v1/organizations").then().statusCode(403);
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("name", "x", "assessmentPeriod", "2026"))
        .when().post("/v1/organizations/{id}/scans", organizationId).then().statusCode(403);

    String scanId = Api.as(token).when().put("/v1/quickscan").then().statusCode(201).extract().path("id");
    answerAll(token, scanId);
    Api.complete(token, scanId);
    Api.as(token).when().put("/v1/quickscan").then().statusCode(200)
        .body("id", equalTo(scanId)).body("status", equalTo("completed"));
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "copy"))
        .when().post("/v1/scans/{id}/revisions", scanId).then().statusCode(403);
  }

  @Test
  void afterUpgradeFullScansWorkButNoRevisionOfTheQuickscan() throws SQLException {
    String token = quickscanToken();
    String organizationId = Api.createOrganization(token);
    String scanId = Api.as(token).when().put("/v1/quickscan").then().statusCode(201).extract().path("id");
    answerAll(token, scanId);
    Api.complete(token, scanId);

    String memberId = Api.as(token).when().get("/v1/me").then().extract().path("id");
    execute("update account_members set plan = 'full' where id = ?", memberId);

    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "copy"))
        .when().post("/v1/scans/{id}/revisions", scanId).then().statusCode(409);
    Api.createScan(token, organizationId, List.of());
    Api.as(token).when().put("/v1/quickscan").then().statusCode(403);
  }

  @Test
  void quickscanContainsOnlyTheChosenDomainsWithAFixedSelection() throws SQLException {
    List<String> chosen = quickscanDomainNames();
    assertThat(chosen).containsExactly("Datastrategie", "Datakwaliteit", "Governance");

    String token = quickscanToken();
    Api.createOrganization(token);
    var scan = Api.as(token).when().put("/v1/quickscan").then().statusCode(201).extract().jsonPath();
    String scanId = scan.getString("id");
    var content = Api.content(token, scanId);
    assertThat(content.getList("domains.name", String.class)).containsExactlyElementsOf(chosen);
    assertThat(content.getList("domains.selected", Boolean.class)).containsOnly(true);

    List<String> selected = scan.getList("selectedDomainIds", String.class);
    Api.as(token).body(Map.of("version", scan.getLong("version"), "selectedDomainIds", selected.subList(0, 1)))
        .when().patch("/v1/scans/{id}", scanId).then().statusCode(422)
        .body("errors[0].field", equalTo("selectedDomainIds"));
    // De beoordeling stuurt de ongewijzigde selectie mee; dat blijft toegestaan.
    Api.as(token).body(Map.of("version", scan.getLong("version"), "selectedDomainIds", selected,
            "goals", List.of()))
        .when().patch("/v1/scans/{id}", scanId).then().statusCode(200);

    // Een latere keuze van de beheerder verandert een bestaande quickscan niet.
    execute("update domains set in_quickscan = true where id = ?", "10000000-0000-0000-0000-000000000004");
    try {
      assertThat(Api.content(token, scanId).getList("domains.name", String.class)).containsExactlyElementsOf(chosen);
    } finally {
      execute("update domains set in_quickscan = false where id = ?", "10000000-0000-0000-0000-000000000004");
    }
  }

  @Test
  void quickscanIsUnavailableWithoutChosenDomains() throws SQLException {
    String token = quickscanToken();
    Api.createOrganization(token);
    List<String> chosen = quickscanDomainIds();
    for (String id : chosen) execute("update domains set in_quickscan = false where id = ?", id);
    try {
      given().when().get("/v1/quickscan/domains").then().statusCode(200).body("domains.size()", equalTo(0));
      Api.as(token).when().put("/v1/quickscan").then().statusCode(422);
    } finally {
      for (String id : chosen) execute("update domains set in_quickscan = true where id = ?", id);
    }
  }

  @Test
  void resultsAndPdfOfAQuickscanNameTheDomainsAFullScanAdds() throws Exception {
    String token = quickscanToken();
    Api.createOrganization(token);
    String scanId = Api.as(token).when().put("/v1/quickscan").then().statusCode(201).extract().path("id");
    answerAll(token, scanId);
    Api.complete(token, scanId);

    var results = Api.as(token).when().get("/v1/scans/{id}/results", scanId).then().statusCode(200)
        .body("kind", equalTo("quick")).extract().jsonPath();
    List<String> notAssessed = results.getList("notAssessedDomains", String.class);
    assertThat(notAssessed).contains("Technologie", "Processen").doesNotContain("Datastrategie");

    byte[] pdf = Api.as(token).when().get("/v1/scans/{id}/export", scanId).then().statusCode(200)
        .extract().asByteArray();
    try (PDDocument document = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
      assertThat(text).contains("Resultaten quickscan datavolwassenheid").contains("Technologie");
    }
    Api.as(Api.demoToken()).when().get("/v1/scans/{id}/results", Api.DEMO_COMPLETED_SCAN).then().statusCode(200)
        .body("kind", equalTo("full")).body("notAssessedDomains.size()", equalTo(0));
  }

  @Test
  void fullAccountHasNoQuickscan() {
    Api.as(Api.newMemberToken()).when().put("/v1/quickscan").then().statusCode(403);
    Api.as(Api.adminToken()).when().put("/v1/quickscan").then().statusCode(403);
  }

  @Test
  void concurrentRequestsCreateOneQuickscan() throws Exception {
    String token = quickscanToken();
    Api.createOrganization(token);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Callable<String[]>> calls = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        calls.add(() -> {
          var response = Api.as(token).when().put("/v1/quickscan").then().extract();
          return new String[] {String.valueOf(response.statusCode()), response.path("id")};
        });
      }
      List<String[]> results = new ArrayList<>();
      for (Future<String[]> future : executor.invokeAll(calls)) {
        results.add(future.get());
      }
      assertThat(results).extracting(result -> result[0]).containsOnly("200", "201").containsOnlyOnce("201");
      assertThat(results).extracting(result -> result[1]).containsOnly(results.getFirst()[1]);
    } finally {
      executor.shutdownNow();
    }
  }

  /** Openbaar endpoint, zonder token. */
  private static List<String> quickscanDomainNames() {
    return given().when().get("/v1/quickscan/domains").then().statusCode(200).extract()
        .jsonPath().getList("domains.name", String.class);
  }

  private static List<String> quickscanDomainIds() {
    return given().when().get("/v1/quickscan/domains").then().statusCode(200).extract()
        .jsonPath().getList("domains.id", String.class);
  }

  private static String quickscanToken() {
    String email = Api.uniqueEmail("quick");
    register(Api.key(), Map.of("accountIdentifier", email, "credential", PASSWORD, "entry", "quickscan"))
        .statusCode(201).body("plan", equalTo("quickscan"));
    return Api.login(email, PASSWORD).getString("accessToken");
  }

  private static io.restassured.response.ValidatableResponse register(String key, Map<String, String> body) {
    return given().contentType(ContentType.JSON).header("X-Idempotency-Key", key).body(body)
        .when().post("/v1/auth/registrations").then();
  }

  /** Alle kennisgebieden van een quickscan zijn geselecteerd; ieder antwoord gebruikt de eerste beschikbare optie. */
  private static void answerAll(String token, String scanId) {
    for (Api.Question question : Api.questions(Api.content(token, scanId), true)) {
      String optionId = question.optionsByScore().values().iterator().next();
      Api.answer(token, scanId, question.questionId(), optionId, 0, "Toelichting");
    }
  }

  private void execute(String sql, String id) throws SQLException {
    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(id));
      statement.executeUpdate();
    }
  }
}
