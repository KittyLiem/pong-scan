package com.example.account;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.account.domain.MemberEntity;
import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LoginLockoutPersistenceTest {

  private static int attempt(String email, String password) {
    return given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", email, "credential", password))
        .when().post("/v1/auth/sessions").then().extract().statusCode();
  }

  @Transactional
  MemberEntity member(String email) {
    return MemberEntity.byIdentifier(email).orElseThrow();
  }

  @Test
  void lockoutOfAnExistingAccountIsPersistedAndNotFlushedByOtherIdentifiers() {
    String email = Api.uniqueEmail("lockout");
    Api.register(email, "juist-wachtwoord");
    for (int i = 0; i < 5; i++) assertThat(attempt(email, "fout-wachtwoord")).isEqualTo(401);

    MemberEntity locked = member(email);
    assertThat(locked.failedLoginCount).isEqualTo(5);
    assertThat(locked.failedLoginWindowEnds).isNotNull();

    // Pogingen met veel andere (onbekende) e-mailadressen mogen de teller van het doelwit niet wegdrukken.
    for (int i = 0; i < 30; i++) attempt(Api.uniqueEmail("ruis"), "fout-wachtwoord");

    assertThat(attempt(email, "juist-wachtwoord")).isEqualTo(429);
  }

  @Test
  void concurrentAttemptsCannotExceedTheLimit() throws Exception {
    String email = Api.uniqueEmail("parallel");
    Api.register(email, "juist-wachtwoord");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(10)) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
      for (int i = 0; i < 10; i++) results.add(executor.submit(() -> attempt(email, "fout-wachtwoord")));
      long unauthorized = 0;
      for (var result : results) if (result.get() == 401) unauthorized++;
      // Elke poging wordt vóór de wachtwoordcontrole atomair gereserveerd: nooit meer dan 5 echte pogingen.
      assertThat(unauthorized).isLessThanOrEqualTo(5);
    }
    assertThat(attempt(email, "juist-wachtwoord")).isEqualTo(429);
  }

  @Test
  void successfulLoginResetsThePersistentCounter() {
    String email = Api.uniqueEmail("reset");
    Api.register(email, "juist-wachtwoord");
    assertThat(attempt(email, "fout-wachtwoord")).isEqualTo(401);
    assertThat(member(email).failedLoginCount).isEqualTo(1);
    assertThat(attempt(email, "juist-wachtwoord")).isEqualTo(201);
    assertThat(member(email).failedLoginCount).isZero();
    assertThat(member(email).failedLoginWindowEnds).isNull();
  }
}
