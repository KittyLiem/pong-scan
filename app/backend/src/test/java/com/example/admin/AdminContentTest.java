package com.example.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.example.audit.AuditEventEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminContentTest {

  @Test
  void listIsPaginatedAndEmbedsQuestionsAndOptions() {
    String admin = Api.adminToken();
    JsonPath first = Api.as(admin).queryParam("pageSize", 2).when().get("/v1/admin/domains")
        .then().statusCode(200).contentType("application/json").extract().jsonPath();
    assertThat(first.getList("items")).hasSize(2);
    assertThat(first.getString("nextCursor")).isNotNull();
    assertThat(first.getBoolean("items[0].required")).isTrue();
    assertThat(first.getString("items[0].name")).isEqualTo("Datastrategie");
    assertThat(first.getList("items[0].questions")).hasSize(3);
    assertThat(first.getList("items[0].questions[0].options")).hasSize(6);
    assertThat(first.getInt("items[0].questions[0].options[5].score")).isEqualTo(5);
    JsonPath second = Api.as(admin).queryParam("pageSize", 2).queryParam("cursor", first.getString("nextCursor"))
        .when().get("/v1/admin/domains").then().statusCode(200).extract().jsonPath();
    assertThat(second.getString("items[0].name")).isEqualTo("Governance");
    Api.as(admin).queryParam("cursor", "!!!").when().get("/v1/admin/domains").then().statusCode(400);
  }

  @Test
  void activeQuestionNeedsAvailableOption() {
    String admin = Api.adminToken();
    JsonPath domain = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Testdomein beheer", "description", "Alleen voor tests", "order", 950, "active", false,
            "version", 0))
        .when().post("/v1/admin/domains")
        .then().statusCode(201).body("id", notNullValue()).body("version", equalTo(0))
        .body("questions.size()", equalTo(0)).extract().jsonPath();
    String domainId = domain.getString("id");

    Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("title", "Actief zonder optie", "text", "Vraag", "order", 1, "active", true, "version", 0))
        .when().post("/v1/admin/domains/{id}/questions", domainId)
        .then().statusCode(422).contentType("application/problem+json");

    JsonPath question = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("title", "Vraag", "text", "Vraagtekst", "order", 1, "active", false, "version", 0))
        .when().post("/v1/admin/domains/{id}/questions", domainId)
        .then().statusCode(201).body("domainId", equalTo(domainId)).extract().jsonPath();
    String questionId = question.getString("id");
    Map<String, Object> activate = Map.of("domainId", domainId, "title", "Vraag", "text", "Vraagtekst", "order", 1,
        "active", true, "version", question.getLong("version"));
    Api.as(admin).body(activate).when().patch("/v1/admin/questions/{id}", questionId).then().statusCode(422);

    Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 6, "label", "Te hoog", "order", 1, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId)
        .then().statusCode(400).body("errors.field", hasItem("score"));
    JsonPath option = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 3, "label", "Gedefinieerd", "order", 1, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId)
        .then().statusCode(201).body("questionId", equalTo(questionId)).extract().jsonPath();
    Api.as(admin).body(activate).when().patch("/v1/admin/questions/{id}", questionId)
        .then().statusCode(200).body("active", equalTo(true)).body("options.size()", equalTo(1));

    // Laatste beschikbare optie van een actieve vraag uitzetten → 422.
    Api.as(admin).body(Map.of("score", 3, "label", "Gedefinieerd", "order", 1, "available", false,
            "version", option.getLong("version")))
        .when().patch("/v1/admin/options/{id}", option.getString("id"))
        .then().statusCode(422).contentType("application/problem+json");
    // Verouderde versie → 409.
    Api.as(admin).body(Map.of("name", "X", "order", 1, "active", false, "version", 99))
        .when().patch("/v1/admin/domains/{id}", domainId).then().statusCode(409);

    long audits = QuarkusTransaction.requiringNew().call(() ->
        AuditEventEntity.count("action like 'admin.%' and outcome = 'success'"));
    assertThat(audits).isGreaterThanOrEqualTo(4);
  }

  @Test
  void deleteIsNotSupported() {
    Api.as(Api.adminToken()).when().delete("/v1/admin/domains/{id}", "10000000-0000-0000-0000-000000000001")
        .then().statusCode(405).contentType("application/problem+json");
  }
  @Test
  void requiredReferenceDomainCannotBeDeactivated() {
    String admin = Api.adminToken();
    JsonPath domain = Api.as(admin).queryParam("pageSize", 1).when().get("/v1/admin/domains")
        .then().statusCode(200).extract().jsonPath();
    Api.as(admin).body(Map.of("name", "Datastrategie", "order", 1, "active", false,
            "version", domain.getLong("items[0].version")))
        .when().patch("/v1/admin/domains/{id}", domain.getString("items[0].id"))
        .then().statusCode(422).contentType("application/problem+json");
    Api.as(admin).when().get("/v1/models/active").then().statusCode(200)
        .body("domains[0].required", equalTo(true));
  }
}
