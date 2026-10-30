package com.example.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.example.priority.domain.DevelopmentPriorityEntity;
import com.example.results.application.PdfWriter;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ResultsApiTest {

  @Test
  void resultsUseSelectedDomainsOnlyWithLevelsAndRecommendations() {
    // Domein 1: scores 4,4,5 → 4,33 sterkte; domein 2: 1,2,1 → 1,33 gap.
    int[] scores = {4, 4, 5, 1, 2, 1};
    Api.CompletedScan done = Api.completedScan(2, index -> scores[index]);
    JsonPath results = Api.as(done.token()).when().get("/v1/scans/{id}/results", done.scanId())
        .then().statusCode(200).contentType("application/json")
        .body("scanId", equalTo(done.scanId()))
        .body("scanName", notNullValue())
        .body("assessmentPeriod", equalTo("Q1 2026"))
        .body("completedAt", notNullValue())
        .extract().jsonPath();
    assertThat(results.getList("domainScores")).hasSize(2);
    assertThat(results.getString("domainScores[0].domainName")).isEqualTo("Datastrategie");
    assertThat(results.getDouble("domainScores[0].score")).isEqualTo(4.33);
    assertThat(results.getInt("domainScores[0].maturityLevel.level")).isEqualTo(4);
    assertThat(results.getString("domainScores[0].maturityLevel.label")).isEqualTo("Beheerst");
    assertThat(results.getDouble("domainScores[1].score")).isEqualTo(1.33);
    assertThat(results.getString("domainScores[1].maturityLevel.label")).isEqualTo("Ad hoc");
    // Totaal = gemiddelde van alle 6 beantwoorde vragen = 17/6 = 2,83.
    assertThat(results.getDouble("totalScore")).isEqualTo(2.83);
    assertThat(results.getString("maturityLevel.label")).isEqualTo("Herhaalbaar");
    assertThat(results.getList("developmentPoints.classification", String.class)).containsExactly("strength", "gap");
    assertThat(results.getString("developmentPoints[1].text")).contains("Datakwaliteit");
    assertThat(results.getString("developmentPoints[1].recommendation")).isNotBlank();
    assertThat(results.getInt("priorities.setRevision")).isZero();
    assertThat(results.getList("priorities.items")).isEmpty();
  }

  @Test
  void resultsBeforeCompletionAreConflict() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, List.of()).getString("id");
    Api.as(token).when().get("/v1/scans/{id}/results", scanId)
        .then().statusCode(409).contentType("application/problem+json")
        .body("type", equalTo("about:blank")).body("status", equalTo(409)).body("title", notNullValue())
        .body("detail", notNullValue()).body("instance", equalTo("/v1/scans/" + scanId + "/results"));
  }

  @Test
  void prioritiesAreVersionedValidatedAndNeverChangeScores() {
    int[] scores = {2, 3, 2, 4, 5, 4};
    Api.CompletedScan done = Api.completedScan(2, index -> scores[index]);
    JsonPath before = Api.as(done.token()).when().get("/v1/scans/{id}/results", done.scanId()).then().extract().jsonPath();
    String gapOrPoint = before.getString("developmentPoints.find { it.classification != 'strength' }.id");
    String strength = before.getString("developmentPoints.find { it.classification == 'strength' }.id");

    JsonPath first = Api.as(done.token()).body(Map.of("items", List.of(Map.of("developmentPointId", gapOrPoint,
            "impact", "high", "urgency", "medium", "selected", true))))
        .when().put("/v1/scans/{id}/development-priorities", done.scanId())
        .then().statusCode(200).extract().jsonPath();
    assertThat(first.getInt("setRevision")).isEqualTo(1);
    JsonPath second = Api.as(done.token()).body(Map.of("items", List.of(Map.of("developmentPointId", gapOrPoint,
            "impact", "low", "urgency", "low", "selected", false))))
        .when().put("/v1/scans/{id}/development-priorities", done.scanId())
        .then().statusCode(200).extract().jsonPath();
    assertThat(second.getInt("setRevision")).isEqualTo(2);

    Api.as(done.token()).queryParam("prioritySetRevision", 1)
        .when().get("/v1/scans/{id}/results", done.scanId()).then().statusCode(200)
        .body("priorities.setRevision", equalTo(1)).body("priorities.items[0].selected", equalTo(true))
        .body("availablePrioritySetRevisions", equalTo(List.of(1, 2)));
    byte[] historicalPdf = Api.as(done.token()).queryParam("prioritySetRevision", 1)
        .when().get("/v1/scans/{id}/export", done.scanId()).then().statusCode(200)
        .contentType("application/pdf").extract().asByteArray();
    assertThat(new String(historicalPdf, StandardCharsets.ISO_8859_1)).contains("versie 1");
    for (String endpoint : List.of("results", "export")) {
      Api.as(done.token()).queryParam("prioritySetRevision", 99)
          .when().get("/v1/scans/{id}/" + endpoint, done.scanId())
          .then().statusCode(404).contentType("application/problem+json");
      Api.as(done.token()).queryParam("prioritySetRevision", 0)
          .when().get("/v1/scans/{id}/" + endpoint, done.scanId())
          .then().statusCode(400).contentType("application/problem+json");
    }

    // Geschiedenis blijft bewaard.
    List<Integer> revisions = QuarkusTransaction.requiringNew().call(() ->
        DevelopmentPriorityEntity.<DevelopmentPriorityEntity>list("scanId", UUID.fromString(done.scanId())).stream()
            .map(item -> item.setRevision).sorted().toList());
    assertThat(revisions).containsExactly(1, 2);

    // Onbekend punt en sterkte → 422.
    Api.as(done.token()).body(Map.of("items", List.of(Map.of("developmentPointId", UUID.randomUUID(),
            "impact", "high", "urgency", "high", "selected", true))))
        .when().put("/v1/scans/{id}/development-priorities", done.scanId())
        .then().statusCode(422).contentType("application/problem+json");
    Api.as(done.token()).body(Map.of("items", List.of(Map.of("developmentPointId", strength,
            "impact", "high", "urgency", "high", "selected", true))))
        .when().put("/v1/scans/{id}/development-priorities", done.scanId())
        .then().statusCode(422);
    Api.as(done.token()).body(Map.of("items", List.of(Map.of("developmentPointId", gapOrPoint,
            "impact", "enorm", "urgency", "high", "selected", true))))
        .when().put("/v1/scans/{id}/development-priorities", done.scanId())
        .then().statusCode(400);

    JsonPath after = Api.as(done.token()).when().get("/v1/scans/{id}/results", done.scanId()).then().extract().jsonPath();
    assertThat(after.getInt("priorities.setRevision")).isEqualTo(2);
    assertThat(after.getBoolean("priorities.items[0].selected")).isFalse();
    assertThat(after.getDouble("totalScore")).isEqualTo(before.getDouble("totalScore"));
    assertThat(after.getList("domainScores")).isEqualTo(before.getList("domainScores"));
    assertThat(after.getList("developmentPoints")).isEqualTo(before.getList("developmentPoints"));
  }

  @Test
  void goalsDoNotChangeScores() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    List<String> domains = Api.activeDomainIds(token).subList(0, 1);
    String withGoals = Api.createScan(token, org, domains).getString("id");
    String withoutGoals = Api.createScan(token, org, domains).getString("id");
    long version = Api.scan(token, withGoals).getLong("version");
    Api.as(token).body(Map.of("version", version, "goals", List.of(
            Map.of("name", "Doel A", "priority", "high", "customText", "Toelichting"),
            Map.of("name", "Doel B", "priority", "low"))))
        .when().patch("/v1/scans/{id}", withGoals).then().statusCode(200);
    for (String scanId : List.of(withGoals, withoutGoals)) {
      Api.answerAll(token, scanId, index -> index + 1);
      Api.complete(token, scanId);
    }
    JsonPath a = Api.as(token).when().get("/v1/scans/{id}/results", withGoals).then().extract().jsonPath();
    JsonPath b = Api.as(token).when().get("/v1/scans/{id}/results", withoutGoals).then().extract().jsonPath();
    assertThat(a.getList("goals")).hasSize(2);
    assertThat(b.getList("goals")).isEmpty();
    assertThat(a.getDouble("totalScore")).isEqualTo(b.getDouble("totalScore"));
    assertThat(a.getList("domainScores.score")).isEqualTo(b.getList("domainScores.score"));
  }

  @Test
  void pdfExportIsDutchAndContainsGoalsAndPrioritiesSection() {
    Api.CompletedScan done = Api.completedScan(2, index -> 3);
    byte[] pdf = Api.as(done.token()).when().get("/v1/scans/{id}/export", done.scanId())
        .then().statusCode(200).contentType("application/pdf").extract().asByteArray();
    String text = new String(pdf, StandardCharsets.ISO_8859_1);
    assertThat(text).startsWith("%PDF-1.4");
    assertThat(text).contains("Resultaten datavolwassenheidsscan");
    assertThat(text).contains("Scores per kennisgebied");
    assertThat(text).contains("Organisatiedoelen en prioriteiten");
    assertThat(text.indexOf("Organisatiedoelen en prioriteiten")).isGreaterThan(text.indexOf("Scores per kennisgebied"));
    assertThat(text).contains("/Encoding /WinAnsiEncoding");
    // "beïnvloeden": ï = 0xEF = octaal 357 in WinAnsi.
    assertThat(text).contains("be\\357nvloeden");
  }

  @Test
  void exportOfDemoScanWorks() {
    byte[] pdf = Api.as(Api.demoToken()).when().get("/v1/scans/{id}/export", Api.DEMO_COMPLETED_SCAN)
        .then().statusCode(200).extract().asByteArray();
    assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("Nulmeting datavolwassenheid");
    JsonPath results = Api.as(Api.demoToken()).when().get("/v1/scans/{id}/results", Api.DEMO_COMPLETED_SCAN)
        .then().statusCode(200).extract().jsonPath();
    assertThat(results.getList("domainScores")).hasSize(4);
    assertThat(results.getList("goals")).hasSize(2);
  }

  @Test
  void pdfWriterPaginatesLongContent() {
    PdfWriter writer = new PdfWriter().title("Lang document");
    for (int index = 0; index < 200; index++) {
      writer.text("Regel " + index + " met een behoorlijk lange tekst die over meerdere regels moet worden "
          + "afgebroken omdat hij niet op één regel van een A4-pagina past, zelfs niet bij een klein lettertype.");
    }
    String pdf = new String(writer.toBytes(), StandardCharsets.ISO_8859_1);
    int count = Integer.parseInt(pdf.replaceAll("(?s).*?/Count (\\d+).*", "$1"));
    assertThat(count).isGreaterThan(5);
    assertThat(pdf).contains("Pagina 1 van " + count);
  }
  @Test
  void earlierPrioritySetsCanBeRetrievedHistorically() {
    Api.CompletedScan done = Api.completedScan(1, index -> 2);
    String pointId = Api.as(done.token()).when().get("/v1/scans/{id}/results", done.scanId())
        .then().statusCode(200).extract().path("developmentPoints[0].id");
    for (int revision = 1; revision <= 2; revision++) {
      Map<String, Object> item = Map.of("developmentPointId", pointId, "impact", revision == 1 ? "high" : "low",
          "urgency", "medium", "selected", true);
      Api.as(done.token()).body(Map.of("items", List.of(item)))
          .when().put("/v1/scans/{id}/development-priorities", done.scanId())
          .then().statusCode(200).body("setRevision", equalTo(revision));
    }
    Api.as(done.token()).queryParam("prioritySetRevision", 1)
        .when().get("/v1/scans/{id}/results", done.scanId()).then().statusCode(200)
        .body("priorities.setRevision", equalTo(1)).body("priorities.items[0].impact", equalTo("high"))
        .body("availablePrioritySetRevisions", equalTo(List.of(1, 2)));
  }
}
