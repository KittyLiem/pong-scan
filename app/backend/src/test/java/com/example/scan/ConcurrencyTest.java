package com.example.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.content.domain.AnswerOptionEntity;
import com.example.priority.domain.PrioritySetEntity;
import com.example.scan.domain.ScanEntity;
import com.example.support.Api;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Deterministische race-tests: een tweede transactie houdt een PESSIMISTIC_WRITE-lock vast en voert de
 * concurrerende wijziging uit; het HTTP-verzoek moet wachten en daarna de nieuwe toestand respecteren.
 */
@QuarkusTest
class ConcurrencyTest {

  /** Houdt een lock in een aparte transactie vast tot {@code release} wordt geteld; voert {@code change} uit. */
  private static CompletableFuture<Void> holdLock(Runnable change, CountDownLatch locked, CountDownLatch release) {
    return CompletableFuture.runAsync(() -> QuarkusTransaction.requiringNew().run(() -> {
      change.run();
      Panache.getEntityManager().flush();
      locked.countDown();
      try {
        release.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }));
  }

  private static Response blockedThenReleased(CompletableFuture<Response> request, CountDownLatch release,
      CompletableFuture<Void> holder) throws Exception {
    Thread.sleep(700);
    assertThat(request).as("verzoek moet op de lock wachten").isNotDone();
    release.countDown();
    holder.get(20, TimeUnit.SECONDS);
    return request.get(20, TimeUnit.SECONDS);
  }

  @Test
  void answerWaitsForConcurrentCompletionAndThenConflicts() throws Exception {
    String token = Api.newMemberToken();
    String org = Api.createOrganization(token);
    String scanId = Api.createScan(token, org, Api.activeDomainIds(token).subList(0, 1)).getString("id");
    Api.Question question = Api.questions(Api.content(token, scanId), true).getFirst();

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder = holdLock(() -> {
      ScanEntity scan = ScanEntity.findById(UUID.fromString(scanId), LockModeType.PESSIMISTIC_WRITE);
      scan.status = ScanEntity.COMPLETED;
      scan.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }, locked, release);
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Response> request = CompletableFuture.supplyAsync(() -> Api.as(token)
        .body(Map.of("optionId", question.optionsByScore().get(2), "version", 0, "explanation", "race"))
        .when().put("/v1/scans/{s}/answers/{q}", scanId, question.questionId()));
    Response response = blockedThenReleased(request, release, holder);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(response.contentType()).startsWith("application/problem+json");
    assertThat(Api.content(token, scanId).getString("domains[0].questions[0].answer")).isNull();
  }

  @Test
  void disablingOptionWaitsForConcurrentChangeAndKeepsInvariant() throws Exception {
    String admin = Api.adminToken();
    String domainId = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("name", "Concurrency-domein", "order", 960, "active", false, "version", 0))
        .when().post("/v1/admin/domains").then().statusCode(201).extract().path("id");
    JsonPath question = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("title", "V", "text", "Vraag", "order", 1, "active", false, "version", 0))
        .when().post("/v1/admin/domains/{id}/questions", domainId).then().statusCode(201).extract().jsonPath();
    String questionId = question.getString("id");
    String optionA = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 1, "label", "A", "order", 1, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId).then().statusCode(201).extract().path("id");
    JsonPath optionB = Api.as(admin).header("X-Idempotency-Key", Api.key())
        .body(Map.of("score", 2, "label", "B", "order", 2, "available", true, "version", 0))
        .when().post("/v1/admin/questions/{id}/options", questionId).then().statusCode(201).extract().jsonPath();
    Api.as(admin).body(Map.of("domainId", domainId, "title", "V", "text", "Vraag", "order", 1, "active", true,
            "version", question.getLong("version")))
        .when().patch("/v1/admin/questions/{id}", questionId).then().statusCode(200);

    // Concurrerende transactie lockt de vraag en zet optie A uit (zoals een gelijktijdig adminverzoek zou doen).
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder = holdLock(() -> {
      Panache.getEntityManager().find(com.example.content.domain.QuestionEntity.class, UUID.fromString(questionId),
          LockModeType.PESSIMISTIC_WRITE);
      AnswerOptionEntity a = AnswerOptionEntity.findById(UUID.fromString(optionA));
      a.available = false;
    }, locked, release);
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Response> request = CompletableFuture.supplyAsync(() -> Api.as(admin)
        .body(Map.of("score", 2, "label", "B", "order", 2, "available", false, "version", optionB.getLong("version")))
        .when().patch("/v1/admin/options/{id}", optionB.getString("id")));
    Response response = blockedThenReleased(request, release, holder);

    assertThat(response.statusCode()).isEqualTo(422);
    long available = QuarkusTransaction.requiringNew().call(() ->
        AnswerOptionEntity.count("questionId = ?1 and available = true", UUID.fromString(questionId)));
    assertThat(available).isEqualTo(1);
  }

  @Test
  void duplicatePrioritySetRevisionIsImpossible() {
    Api.CompletedScan done = Api.completedScan(1, index -> 1);
    UUID scanId = UUID.fromString(done.scanId());
    Runnable insert = () -> QuarkusTransaction.requiringNew().run(() -> {
      PrioritySetEntity set = new PrioritySetEntity();
      set.id = UUID.randomUUID();
      set.scanId = scanId;
      set.setRevision = 1;
      set.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
      set.persist();
    });
    insert.run();
    assertThatThrownBy(insert::run).isInstanceOf(RuntimeException.class);
  }
}
