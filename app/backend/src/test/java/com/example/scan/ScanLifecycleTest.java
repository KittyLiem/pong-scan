package com.example.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import com.example.audit.AuditEventEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScanLifecycleTest {

  @Test
  void createSnapshotsActiveContentAndMapsInitialSelection() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    List<String> managed = Api.activeDomainIds(token);
    JsonPath scan = Api.createScan(token, org, managed.subList(0, 2));
    assertThat(scan.getString("status")).isEqualTo("concept");
    assertThat(scan.getInt("progress")).isZero();
    assertThat(scan.getInt("revision")).isEqualTo(1);
    assertThat(scan.getString("revisionOf")).isNull();
    List<String> selected = scan.getList("selectedDomainIds", String.class);
    assertThat(selected).hasSize(2).doesNotContainAnyElementsOf(managed);

    JsonPath content = Api.content(token, scan.getString("id"));
    assertThat(content.getBoolean("editable")).isTrue();
    assertThat(content.getList("domains")).hasSize(managed.size());
    assertThat(content.getList("domains.findAll { it.selected }.id", String.class))
        .containsExactlyElementsOf(selected);
    assertThat(content.getString("domains[0].name")).isEqualTo("Datastrategie");
    assertThat(content.getInt("domains[0].questionCount")).isEqualTo(3);
    assertThat(content.getList("domains[0].questions[0].options")).hasSize(6);
    assertThat(content.getString("domains[0].questions[0].options[0].label")).isEqualTo("Niet ingericht");
    assertThat(content.getString("domains[0].questions[0].answer")).isNull();
  }

  @Test
  void unknownInitialDomainIsUnprocessable() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    Api.as(token).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Scan", "assessmentPeriod", "2026", "initialSelectedDomainIds", List.of(UUID.randomUUID())))
        .when().post("/v1/organizations/{id}/scans", org)
        .then().statusCode(422).contentType("application/problem+json");
  }

  @Test
  void createValidatesPeriodAndName() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    Api.as(token).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "", "assessmentPeriod", "voorjaar"))
        .when().post("/v1/organizations/{id}/scans", org)
        .then().statusCode(400).contentType("application/problem+json")
        .body("errors.field", hasItem("name"))
        .body("errors.field", hasItem("assessmentPeriod"));
  }

  @Test
  void statusMovesFromConceptToInProgressToContentCompleteToCompleted() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, Api.activeDomainIds(token).subList(0, 1)).getString("id");
    List<Api.Question> questions = Api.questions(Api.content(token, scanId), true);
    assertThat(questions).hasSize(3);

    JsonPath first = Api.answer(token, scanId, questions.get(0).questionId(), questions.get(0).optionsByScore().get(2),
        0, "Eerste toelichting");
    assertThat(first.getString("scanStatus")).isEqualTo("in-progress");
    assertThat(first.getInt("progress")).isEqualTo(33);
    assertThat(first.getLong("version")).isEqualTo(1);
    long scanVersion = Api.scan(token, scanId).getLong("version");
    assertThat(first.getLong("scanVersion")).isEqualTo(scanVersion);

    // Antwoord zonder toelichting telt mee voor voortgang, maar niet voor "content-complete".
    Api.answer(token, scanId, questions.get(1).questionId(), questions.get(1).optionsByScore().get(3), 0, "Tweede");
    JsonPath third = Api.answer(token, scanId, questions.get(2).questionId(), questions.get(2).optionsByScore().get(4),
        0, "  ");
    assertThat(third.getInt("progress")).isEqualTo(100);
    assertThat(third.getString("scanStatus")).isEqualTo("in-progress");

    // Afronden zonder volledige toelichting → 422.
    Api.as(token).body(Map.of("version", scanVersion))
        .when().post("/v1/scans/{id}/completion", scanId)
        .then().statusCode(422).contentType("application/problem+json");

    JsonPath updated = Api.answer(token, scanId, questions.get(2).questionId(),
        questions.get(2).optionsByScore().get(4), 1, "Nu wel toegelicht");
    assertThat(updated.getString("scanStatus")).isEqualTo("content-complete");
    assertThat(updated.getLong("version")).isEqualTo(2);

    JsonPath completed = Api.complete(token, scanId);
    assertThat(completed.getString("status")).isEqualTo("completed");
    assertThat(completed.getInt("progress")).isEqualTo(100);
    assertThat(completed.getString("completedAt")).isNotNull();
    assertThat(Api.content(token, scanId).getBoolean("editable")).isFalse();
    long audits = QuarkusTransaction.requiringNew().call(() ->
        AuditEventEntity.count("action = ?1 and resourceId = ?2 and outcome = ?3", "scan.complete",
            UUID.fromString(scanId), "success"));
    assertThat(audits).isEqualTo(1);
  }

  @Test
  void answerOnCompletedScanIsConflict() {
    Api.CompletedScan done = Api.completedScan(1, index -> 3);
    Api.Question question = Api.questions(Api.content(done.token(), done.scanId()), true).getFirst();
    Api.as(done.token()).body(Map.of("optionId", question.optionsByScore().get(1), "version", 1,
            "explanation", "te laat"))
        .when().put("/v1/scans/{s}/answers/{q}", done.scanId(), question.questionId())
        .then().statusCode(409).contentType("application/problem+json").body("status", equalTo(409));
  }

  @Test
  void optionOfAnotherQuestionIsUnprocessable() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, Api.activeDomainIds(token).subList(0, 1)).getString("id");
    List<Api.Question> questions = Api.questions(Api.content(token, scanId), true);
    Api.as(token).body(Map.of("optionId", questions.get(1).optionsByScore().get(2), "version", 0))
        .when().put("/v1/scans/{s}/answers/{q}", scanId, questions.get(0).questionId())
        .then().statusCode(422).contentType("application/problem+json");
    Api.as(token).body(Map.of("optionId", UUID.randomUUID(), "version", 0))
        .when().put("/v1/scans/{s}/answers/{q}", scanId, questions.get(0).questionId())
        .then().statusCode(422);
  }

  @Test
  void questionOfUnselectedDomainIsUnprocessable() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, Api.activeDomainIds(token).subList(0, 1)).getString("id");
    List<Api.Question> unselected = Api.questions(Api.content(token, scanId), false).stream()
        .filter(question -> Api.questions(Api.content(token, scanId), true).stream()
            .noneMatch(selected -> selected.questionId().equals(question.questionId())))
        .toList();
    Api.Question question = unselected.getFirst();
    Api.as(token).body(Map.of("optionId", question.optionsByScore().get(2), "version", 0))
        .when().put("/v1/scans/{s}/answers/{q}", scanId, question.questionId())
        .then().statusCode(422).contentType("application/problem+json");
  }

  @Test
  void completionSucceedsWhileUnselectedDomainsAreEmpty() {
    Api.CompletedScan done = Api.completedScan(2, index -> 2);
    JsonPath content = Api.content(done.token(), done.scanId());
    assertThat(content.getList("domains.findAll { !it.selected }")).isNotEmpty();
    assertThat(content.getList("domains.findAll { !it.selected }.answeredCount", Integer.class)).containsOnly(0);
    assertThat(Api.scan(done.token(), done.scanId()).getString("status")).isEqualTo("completed");
  }

  @Test
  void staleAnswerVersionIsConflict() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, Api.activeDomainIds(token).subList(0, 1)).getString("id");
    Api.Question question = Api.questions(Api.content(token, scanId), true).getFirst();
    Api.answer(token, scanId, question.questionId(), question.optionsByScore().get(1), 0, "eerste");
    Api.as(token).body(Map.of("optionId", question.optionsByScore().get(2), "version", 0))
        .when().put("/v1/scans/{s}/answers/{q}", scanId, question.questionId())
        .then().statusCode(409).contentType("application/problem+json").body("title", equalTo("Versieconflict"));
  }

  @Test
  void patchUpdatesSelectionGoalsAndRejectsCompletedOrStale() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    JsonPath created = Api.createScan(token, org, List.of());
    String scanId = created.getString("id");
    String domain = Api.content(token, scanId).getString("domains[2].id");
    JsonPath patched = Api.as(token).body(Map.of("version", created.getLong("version"),
            "name", "Nieuwe naam", "selectedDomainIds", List.of(domain),
            "goals", List.of(Map.of("name", "Betere data", "priority", "high"))))
        .when().patch("/v1/scans/{id}", scanId)
        .then().statusCode(200).extract().jsonPath();
    assertThat(patched.getString("name")).isEqualTo("Nieuwe naam");
    assertThat(patched.getList("selectedDomainIds", String.class)).containsExactly(domain);
    assertThat(patched.getString("goals[0].priority")).isEqualTo("high");
    assertThat(patched.getLong("version")).isGreaterThan(created.getLong("version"));

    Api.as(token).body(Map.of("version", created.getLong("version"), "name", "Oud"))
        .when().patch("/v1/scans/{id}", scanId).then().statusCode(409);
    Api.as(token).body(Map.of("version", patched.getLong("version"), "selectedDomainIds", List.of()))
        .when().patch("/v1/scans/{id}", scanId).then().statusCode(400)
        .body("errors.field", hasItem("selectedDomainIds"));
    Api.as(token).body(Map.of("version", patched.getLong("version"), "selectedDomainIds", List.of(UUID.randomUUID())))
        .when().patch("/v1/scans/{id}", scanId).then().statusCode(422);

    Api.CompletedScan done = Api.completedScan(1, index -> 1);
    long version = Api.scan(done.token(), done.scanId()).getLong("version");
    Api.as(done.token()).body(Map.of("version", version, "name", "Mag niet"))
        .when().patch("/v1/scans/{id}", done.scanId()).then().statusCode(409)
        .contentType("application/problem+json");
  }

  @Test
  void listIsPaginatedAndSortedByUpdatedAt() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    for (int index = 0; index < 3; index++) Api.createScan(token, org, List.of());
    JsonPath first = Api.as(token).queryParam("pageSize", 2).when().get("/v1/organizations/{id}/scans", org)
        .then().statusCode(200).extract().jsonPath();
    assertThat(first.getList("items")).hasSize(2);
    assertThat(first.getString("nextCursor")).isNotNull();
    JsonPath second = Api.as(token).queryParam("pageSize", 2).queryParam("cursor", first.getString("nextCursor"))
        .when().get("/v1/organizations/{id}/scans", org).then().statusCode(200).extract().jsonPath();
    assertThat(second.getList("items")).hasSize(1);
    assertThat(second.getString("nextCursor")).isNull();
    Api.as(token).queryParam("pageSize", 101).when().get("/v1/organizations/{id}/scans", org).then().statusCode(400);
  }

  @Test
  void createIsIdempotent() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String key = Api.key();
    Map<String, Object> body = Map.of("name", "Idempotent", "assessmentPeriod", "H1 2026");
    String first = Api.as(token).header("X-Idempotency-Key", key).body(body)
        .when().post("/v1/organizations/{id}/scans", org).then().statusCode(201).extract().path("id");
    String second = Api.as(token).header("X-Idempotency-Key", key).body(body)
        .when().post("/v1/organizations/{id}/scans", org).then().statusCode(201).extract().path("id");
    assertThat(second).isEqualTo(first);
    Api.as(token).header("X-Idempotency-Key", key).body(Map.of("name", "Anders", "assessmentPeriod", "2026"))
        .when().post("/v1/organizations/{id}/scans", org).then().statusCode(409);
  }
}
