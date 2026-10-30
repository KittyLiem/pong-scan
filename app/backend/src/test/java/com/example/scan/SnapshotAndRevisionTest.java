package com.example.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SnapshotAndRevisionTest {

  private static String results(String token, String scanId) {
    return Api.as(token).when().get("/v1/scans/{id}/results", scanId).then().statusCode(200).extract().asString();
  }

  @Test
  void adminChangesNeverAffectCompletedScanResults() {
    String admin = Api.adminToken();
    // Eigen beheerd domein met één vraag en twee opties, zodat de referentie-inhoud intact blijft.
    String domainId = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Regressiedomein", "order", 900, "active", true, "version", 0))
        .when().post("/v1/admin/domains").then().statusCode(201).extract().path("id");
    JsonPath question = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("title", "Regressievraag", "text", "Hoe goed is dit geregeld?", "order", 1, "active", false,
            "version", 0))
        .when().post("/v1/admin/domains/{id}/questions", domainId).then().statusCode(201).extract().jsonPath();
    String questionId = question.getString("id");
    JsonPath option = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 4, "label", "Beheerst", "order", 1, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId).then().statusCode(201).extract().jsonPath();
    Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 1, "label", "Ad hoc", "order", 2, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId).then().statusCode(201);
    JsonPath activated = Api.as(admin)
        .body(Map.of("domainId", domainId, "title", "Regressievraag", "text", "Hoe goed is dit geregeld?",
            "order", 1, "active", true, "version", question.getLong("version")))
        .when().patch("/v1/admin/questions/{id}", questionId).then().statusCode(200).extract().jsonPath();

    // Lid maakt een scan met het eerste referentiedomein én het regressiedomein en rondt af.
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    List<String> active = Api.activeDomainIds(token);
    assertThat(active).contains(domainId);
    String scanId = Api.createScan(token, org, List.of(active.getFirst(), domainId)).getString("id");
    List<Api.Question> questions = Api.questions(Api.content(token, scanId), true);
    for (Api.Question item : questions) {
      int score = item.optionsByScore().containsKey(4) ? 4 : 3;
      Api.answer(token, scanId, item.questionId(), item.optionsByScore().get(score), 0, "Toelichting");
    }
    Api.complete(token, scanId);
    String resultsBefore = results(token, scanId);
    String contentBefore = Api.as(token).when().get("/v1/scans/{id}/content", scanId).then().extract().asString();
    byte[] pdfBefore = Api.as(token).when().get("/v1/scans/{id}/export", scanId).then().extract().asByteArray();
    assertThat(resultsBefore).contains("Regressiedomein");

    // Beheer wijzigt: domein hernoemen, optiescore wijzigen, vraag deactiveren.
    JsonPath domains = Api.as(admin).queryParam("pageSize", 100).when().get("/v1/admin/domains")
        .then().statusCode(200).extract().jsonPath();
    long domainVersion = domains.getLong("items.find { it.id == '" + domainId + "' }.version");
    Api.as(admin).body(Map.of("name", "Hernoemd domein", "order", 900, "active", true, "version", domainVersion))
        .when().patch("/v1/admin/domains/{id}", domainId).then().statusCode(200);
    Api.as(admin).body(Map.of("score", 0, "label", "Beheerst", "order", 1, "available", true,
            "version", option.getLong("version")))
        .when().patch("/v1/admin/options/{id}", option.getString("id")).then().statusCode(200);
    Api.as(admin)
        .body(Map.of("domainId", domainId, "title", "Regressievraag", "text", "Hoe goed is dit geregeld?",
            "order", 1, "active", false, "version", activated.getLong("version")))
        .when().patch("/v1/admin/questions/{id}", questionId).then().statusCode(200);

    assertThat(results(token, scanId)).isEqualTo(resultsBefore);
    assertThat(Api.as(token).when().get("/v1/scans/{id}/content", scanId).then().extract().asString())
        .isEqualTo(contentBefore);
    String pdfAfter = new String(Api.as(token).when().get("/v1/scans/{id}/export", scanId).then().extract()
        .asByteArray(), StandardCharsets.ISO_8859_1);
    // De PDF bevat geen tijdsafhankelijke gegevens behalve de afrondingsdatum, dus ook die is identiek.
    assertThat(pdfAfter).isEqualTo(new String(pdfBefore, StandardCharsets.ISO_8859_1));

    // Een nieuwe scan bevat het gedeactiveerde domein niet meer (geen actieve vraag).
    assertThat(Api.activeDomainIds(token)).doesNotContain(domainId);
  }

  @Test
  void revisionLeavesOriginalUntouched() {
    Api.CompletedScan done = Api.completedScan(2, index -> 2);
    String token = done.token();
    String originalBefore = Api.as(token).when().get("/v1/scans/{id}", done.scanId()).then().extract().asString();
    String resultsBefore = results(token, done.scanId());

    JsonPath copy = Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "copy"))
        .when().post("/v1/scans/{id}/revisions", done.scanId())
        .then().statusCode(201).extract().jsonPath();
    assertThat(copy.getString("id")).isNotEqualTo(done.scanId());
    assertThat(copy.getString("revisionOf")).isEqualTo(done.scanId());
    assertThat(copy.getInt("revision")).isEqualTo(2);
    assertThat(copy.getString("status")).isEqualTo("content-complete");
    assertThat(copy.getInt("progress")).isEqualTo(100);
    assertThat(copy.getList("selectedDomainIds")).hasSize(2);

    JsonPath fresh = Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "new-revision"))
        .when().post("/v1/scans/{id}/revisions", done.scanId())
        .then().statusCode(201).extract().jsonPath();
    assertThat(fresh.getString("status")).isEqualTo("concept");
    assertThat(fresh.getInt("progress")).isZero();
    assertThat(Api.content(token, fresh.getString("id")).getList("domains.answeredCount", Integer.class))
        .containsOnly(0);

    // Antwoord wijzigen in de kopie raakt het origineel niet.
    Api.Question question = Api.questions(Api.content(token, copy.getString("id")), true).getFirst();
    Api.answer(token, copy.getString("id"), question.questionId(), question.optionsByScore().get(5), 1, "Herzien");

    assertThat(Api.as(token).when().get("/v1/scans/{id}", done.scanId()).then().extract().asString())
        .isEqualTo(originalBefore);
    assertThat(results(token, done.scanId())).isEqualTo(resultsBefore);

    // Alleen afgeronde scans kunnen worden herzien.
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "copy"))
        .when().post("/v1/scans/{id}/revisions", copy.getString("id"))
        .then().statusCode(409).contentType("application/problem+json");
    Api.as(token).header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "reopen"))
        .when().post("/v1/scans/{id}/revisions", done.scanId())
        .then().statusCode(400);
  }
}
