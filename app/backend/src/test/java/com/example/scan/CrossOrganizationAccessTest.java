package com.example.scan;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Een lid van een andere organisatie krijgt 404 (geen informatielek) op alle scan-endpoints. */
@QuarkusTest
class CrossOrganizationAccessTest {

  @Test
  void otherMemberGetsNotFoundEverywhere() {
    Api.CompletedScan completed = Api.completedScan(1, index -> 3);
    String owner = completed.token();
    String scanId = completed.scanId();
    Api.Question question = Api.questions(Api.content(owner, scanId), true).getFirst();
    String intruder = Api.newMemberToken();

    record Call(String name, Function<RequestSpecification, io.restassured.response.Response> call) {}
    List<Call> calls = List.of(
        new Call("get", spec -> spec.get("/v1/scans/{id}", scanId)),
        new Call("content", spec -> spec.get("/v1/scans/{id}/content", scanId)),
        new Call("update", spec -> spec.body(Map.of("version", 0, "name", "x")).patch("/v1/scans/{id}", scanId)),
        new Call("answer", spec -> spec.body(Map.of("optionId", question.optionsByScore().get(1), "version", 0))
            .put("/v1/scans/{id}/answers/{q}", scanId, question.questionId())),
        new Call("complete", spec -> spec.body(Map.of("version", 0)).post("/v1/scans/{id}/completion", scanId)),
        new Call("revision", spec -> spec.header("X-Idempotency-Key", Api.key()).body(Map.of("mode", "copy"))
            .post("/v1/scans/{id}/revisions", scanId)),
        new Call("results", spec -> spec.get("/v1/scans/{id}/results", scanId)),
        new Call("export", spec -> spec.get("/v1/scans/{id}/export", scanId)),
        new Call("priorities", spec -> spec.body(Map.of("items", List.of(Map.of("developmentPointId", UUID.randomUUID(),
                "impact", "high", "urgency", "low", "selected", true))))
            .put("/v1/scans/{id}/development-priorities", scanId)),
        new Call("list", spec -> spec.get("/v1/organizations/{id}/scans", completed.organizationId())),
        new Call("create", spec -> spec.header("X-Idempotency-Key", Api.key())
            .body(Map.of("name", "x", "assessmentPeriod", "2026"))
            .post("/v1/organizations/{id}/scans", completed.organizationId())));

    for (Call call : calls) {
      call.call().apply(Api.as(intruder).when())
          .then().statusCode(404).contentType("application/problem+json").body("status", equalTo(404));
    }
    // De eigenaar heeft wel toegang.
    Api.as(owner).when().get("/v1/scans/{id}/results", scanId).then().statusCode(200);
  }

  @Test
  void organizationsListShowsOnlyOwnOrganizations() {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    Api.as(token).when().get("/v1/organizations").then().statusCode(200)
        .body("items.size()", equalTo(1)).body("items[0].id", equalTo(org));
    Api.as(Api.demoToken()).when().get("/v1/organizations").then().statusCode(200)
        .body("items.id", not(hasItem(org)));
  }
}
