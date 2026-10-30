package com.example.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Hulpfuncties voor integratietests via de echte HTTP-API. */
public final class Api {
  private Api() {}

  public static final String DEMO_USER = "demo@pong.example";
  public static final String ADMIN_USER = "admin@pong.example";
  public static final String DEMO_PASSWORD = "password";
  public static final String DEMO_COMPLETED_SCAN = "91000000-0000-0000-0000-000000000001";
  public static final String DEMO_ORGANIZATION = "90000000-0000-0000-0000-000000000010";

  public static String key() {
    return UUID.randomUUID().toString();
  }

  public static RequestSpecification as(String token) {
    return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
  }

  public static String uniqueEmail(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
  }

  public static void register(String email, String password) {
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", key())
        .body(Map.of("accountIdentifier", email, "credential", password))
        .when().post("/v1/auth/registrations")
        .then().statusCode(201);
  }

  public static JsonPath login(String email, String password) {
    return given().contentType(ContentType.JSON).header("X-Idempotency-Key", key())
        .body(Map.of("accountIdentifier", email, "credential", password))
        .when().post("/v1/auth/sessions")
        .then().statusCode(201).extract().jsonPath();
  }

  public static String newMemberToken() {
    String email = uniqueEmail("member");
    register(email, "geheim-wachtwoord");
    return login(email, "geheim-wachtwoord").getString("accessToken");
  }

  public static String demoToken() {
    return login(DEMO_USER, DEMO_PASSWORD).getString("accessToken");
  }

  public static String adminToken() {
    return login(ADMIN_USER, DEMO_PASSWORD).getString("accessToken");
  }

  public static String createOrganization(String token) {
    return as(token).header("X-Idempotency-Key", key())
        .body(Map.of("name", "Testorganisatie " + UUID.randomUUID().toString().substring(0, 6)))
        .when().post("/v1/organizations")
        .then().statusCode(201).extract().path("id");
  }

  /** Beheerde domein-id's van het actieve model, op volgorde. */
  public static List<String> activeDomainIds(String token) {
    return as(token).when().get("/v1/models/active").then().statusCode(200).extract().jsonPath()
        .getList("domains.id", String.class);
  }

  public static JsonPath createScan(String token, String organizationId, List<String> managedDomainIds) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "Testscan");
    body.put("assessmentPeriod", "Q1 2026");
    body.put("initialSelectedDomainIds", managedDomainIds);
    return as(token).header("X-Idempotency-Key", key()).body(body)
        .when().post("/v1/organizations/{id}/scans", organizationId)
        .then().statusCode(201).extract().jsonPath();
  }

  public static JsonPath content(String token, String scanId) {
    return as(token).when().get("/v1/scans/{id}/content", scanId).then().statusCode(200).extract().jsonPath();
  }

  public static JsonPath scan(String token, String scanId) {
    return as(token).when().get("/v1/scans/{id}", scanId).then().statusCode(200).extract().jsonPath();
  }

  /** Alle vragen van geselecteerde domeinen: [questionId, optionId-met-score]. */
  public record Question(String domainId, String questionId, Map<Integer, String> optionsByScore) {}

  @SuppressWarnings("unchecked")
  public static List<Question> questions(JsonPath content, boolean selectedOnly) {
    List<Question> result = new ArrayList<>();
    List<Map<String, Object>> domains = content.getList("domains");
    for (Map<String, Object> domain : domains) {
      if (selectedOnly && !Boolean.TRUE.equals(domain.get("selected"))) continue;
      for (Map<String, Object> question : (List<Map<String, Object>>) domain.get("questions")) {
        Map<Integer, String> options = new LinkedHashMap<>();
        for (Map<String, Object> option : (List<Map<String, Object>>) question.get("options")) {
          options.putIfAbsent((Integer) option.get("score"), (String) option.get("id"));
        }
        result.add(new Question((String) domain.get("id"), (String) question.get("id"), options));
      }
    }
    return result;
  }

  public static JsonPath answer(String token, String scanId, String questionId, String optionId, long version,
      String explanation) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("optionId", optionId);
    body.put("explanation", explanation);
    body.put("evidenceReferences", List.of("https://bewijs.example.test/1"));
    body.put("version", version);
    return as(token).body(body)
        .when().put("/v1/scans/{scanId}/answers/{questionId}", scanId, questionId)
        .then().statusCode(200).extract().jsonPath();
  }

  /** Beantwoordt alle vragen in geselecteerde domeinen met de score uit de functie (index in de lijst). */
  public static void answerAll(String token, String scanId, ToIntFunction<Integer> scoreForIndex) {
    List<Question> questions = questions(content(token, scanId), true);
    for (int index = 0; index < questions.size(); index++) {
      Question question = questions.get(index);
      int score = scoreForIndex.applyAsInt(index);
      answer(token, scanId, question.questionId(), question.optionsByScore().get(score), 0, "Toelichting " + index);
    }
  }

  public static JsonPath complete(String token, String scanId) {
    long version = scan(token, scanId).getLong("version");
    return as(token).header("X-Idempotency-Key", key())
        .body(Map.of("version", version, "generalComments", "Afgerond in test"))
        .when().post("/v1/scans/{id}/completion", scanId)
        .then().statusCode(200).extract().jsonPath();
  }

  /** Volledige flow: lid, organisatie, scan met de eerste {@code domainCount} domeinen, alles beantwoord, afgerond. */
  public record CompletedScan(String token, String organizationId, String scanId) {}

  public static CompletedScan completedScan(int domainCount, ToIntFunction<Integer> scoreForIndex) {
    String token = newMemberToken();
    String organizationId = createOrganization(token);
    List<String> domains = activeDomainIds(token).subList(0, domainCount);
    String scanId = createScan(token, organizationId, domains).getString("id");
    answerAll(token, scanId, scoreForIndex);
    complete(token, scanId);
    return new CompletedScan(token, organizationId, scanId);
  }
}
