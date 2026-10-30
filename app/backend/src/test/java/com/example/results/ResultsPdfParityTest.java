package com.example.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ResultsPdfParityTest {

  @Test
  void exportedTextMatchesResultsWithGoalsAndSavedPriorities() throws Exception {
    String token = Api.newMemberToken();
    String organization = Api.createOrganization(token);
    String scan = Api.createScan(token, organization, Api.activeDomainIds(token).subList(0, 7)).getString("id");
    Api.as(token).body(Map.of("version", Api.scan(token, scan).getLong("version"), "goals", List.of(
            Map.of("name", "Betere patiëntenzorg", "priority", "high"),
            Map.of("name", "Efficiënter samenwerken", "priority", "medium"))))
        .when().patch("/v1/scans/{id}", scan).then().statusCode(200);
    // Ook gebroken scores en verschillende niveaus: 0,33; 1,33; ...; 4,33.
    Api.answerAll(token, scan, index -> (index / 3) % 5 + (index % 3 == 2 ? 1 : 0));
    Api.complete(token, scan);
    JsonPath before = Api.as(token).when().get("/v1/scans/{id}/results", scan)
        .then().statusCode(200).extract().jsonPath();
    List<String> pointIds = before.getList("developmentPoints.findAll { it.classification != 'strength' }.id", String.class);
    assertThat(pointIds).hasSizeGreaterThanOrEqualTo(3);
    Api.as(token).body(Map.of("items", List.of(
            Map.of("developmentPointId", pointIds.get(0), "impact", "high", "urgency", "medium", "selected", true),
            Map.of("developmentPointId", pointIds.get(1), "impact", "medium", "urgency", "low", "selected", true),
            Map.of("developmentPointId", pointIds.get(2), "impact", "low", "urgency", "high", "selected", true))))
        .when().put("/v1/scans/{id}/development-priorities", scan)
        .then().statusCode(200).body("setRevision", equalTo(1));

    JsonPath results = Api.as(token).queryParam("prioritySetRevision", 1)
        .when().get("/v1/scans/{id}/results", scan).then().statusCode(200).extract().jsonPath();
    byte[] pdf = Api.as(token).queryParam("prioritySetRevision", 1)
        .when().get("/v1/scans/{id}/export", scan).then().statusCode(200)
        .contentType("application/pdf").extract().asByteArray();
    String text;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      // Regelafbreking/paginering mag de inhoud niet beïnvloeden.
      text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
    }
    String heading = "Organisatiedoelen en prioriteiten";
    assertThat(text).contains(heading, number(results.getDouble("totalScore")), results.getString("maturityLevel.label"));
    int headingIndex = text.indexOf(heading);
    List<Map<String, Object>> domains = results.getList("domainScores");
    assertThat(domains).hasSize(7);
    for (int index = 0; index < domains.size(); index++) {
      String domain = "domainScores[" + index + "]";
      String scoreLine = results.getString(domain + ".domainName") + ": "
          + number(results.getDouble(domain + ".score")) + " - niveau "
          + results.getInt(domain + ".maturityLevel.level") + " ("
          + results.getString(domain + ".maturityLevel.label") + ")";
      assertThat(text).contains(scoreLine);
      assertThat(text.indexOf(scoreLine) + scoreLine.length()).as("Domeinscore vóór doelen: %s", scoreLine)
          .isLessThan(headingIndex);
    }
    List<String> goals = results.getList("goals.name", String.class);
    assertThat(goals).hasSize(2);
    goals.forEach(goal -> assertThat(text.substring(headingIndex)).contains(goal));
    assertThat(results.getInt("priorities.setRevision")).isEqualTo(1);
    List<Map<String, Object>> priorities = results.getList("priorities.items");
    assertThat(priorities).hasSize(3);
    for (Map<String, Object> priority : priorities) {
      assertThat(priority.get("selected")).isEqualTo(true);
      String domainName = results.getString("developmentPoints.find { it.id == '"
          + priority.get("developmentPointId") + "' }.domainName");
      assertThat(text.substring(headingIndex)).contains(domainName + ": impact "
          + dutchLevel((String) priority.get("impact")) + ", urgentie " + dutchLevel((String) priority.get("urgency")));
    }
  }

  private static String number(double score) {
    return String.format(Locale.forLanguageTag("nl-NL"), "%.2f", score);
  }

  private static String dutchLevel(String level) {
    return switch (level) {
      case "high" -> "hoog";
      case "medium" -> "middel";
      case "low" -> "laag";
      default -> throw new AssertionError("Onbekende prioriteit: " + level);
    };
  }
}
