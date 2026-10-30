package com.example.account;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import com.example.audit.AuditEventEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.http.ContentType;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AccountApiTest {

  @Test
  void registrationReturnsMemberWithRole() {
    String email = Api.uniqueEmail("registratie");
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", email, "credential", "geheim-wachtwoord"))
        .when().post("/v1/auth/registrations")
        .then().statusCode(201).contentType(ContentType.JSON)
        .body("id", notNullValue())
        .body("accountIdentifier", equalTo(email))
        .body("role", equalTo("member"));
  }

  @Test
  void registrationValidatesInputInDutch() {
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", "geen-email", "credential", "kort"))
        .when().post("/v1/auth/registrations")
        .then().statusCode(400).contentType("application/problem+json")
        .body("title", equalTo("Ongeldige invoer"))
        .body("errors.field", hasItem("accountIdentifier"))
        .body("errors.field", hasItem("credential"))
        .body("errors.message", hasItem("moet een geldig e-mailadres zijn"));
  }

  @Test
  void registrationRequiresIdempotencyKey() {
    given().contentType(ContentType.JSON)
        .body(Map.of("accountIdentifier", Api.uniqueEmail("zonder-sleutel"), "credential", "geheim-wachtwoord"))
        .when().post("/v1/auth/registrations")
        .then().statusCode(400).contentType("application/problem+json")
        .body("errors.field", hasItem("X-Idempotency-Key"));
  }

  @Test
  void sessionContainsExpiryMemberAndDesiredRoute() {
    String email = Api.uniqueEmail("sessie");
    Api.register(email, "geheim-wachtwoord");
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", email, "credential", "geheim-wachtwoord", "desiredRoute", "/scans/123"))
        .when().post("/v1/auth/sessions")
        .then().statusCode(201)
        .body("accessToken", notNullValue())
        .body("tokenType", equalTo("Bearer"))
        .body("expiresAt", notNullValue())
        .body("desiredRoute", equalTo("/scans/123"))
        .body("member.accountIdentifier", equalTo(email))
        .body("member.role", equalTo("member"));
  }

  @Test
  void invalidDesiredRouteIsRejected() {
    for (String route : List.of("https://evil.example", "//evil.example", "/\\evil.example", "scans",
        "/" + "a".repeat(2048))) {
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
          .body(Map.of("accountIdentifier", Api.DEMO_USER, "credential", Api.DEMO_PASSWORD, "desiredRoute", route))
          .when().post("/v1/auth/sessions")
          .then().statusCode(400).contentType("application/problem+json")
          .body("errors.field", hasItem("desiredRoute"));
    }
  }

  @Test
  void wrongCredentialsGive401AndAreAuditedWithoutSecrets() {
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", Api.DEMO_USER, "credential", "fout-wachtwoord-xyz"))
        .when().post("/v1/auth/sessions")
        .then().statusCode(401).contentType("application/problem+json");
    List<AuditEventEntity> failures = QuarkusTransaction.requiringNew().call(() ->
        AuditEventEntity.<AuditEventEntity>list("action = ?1 and outcome = ?2", "session.create", "failure"));
    assertThat(failures).isNotEmpty();
    assertThat(failures).allSatisfy(event -> {
      assertThat(event.correlationId).isNotBlank();
      assertThat(event.action + event.resourceType).doesNotContain("fout-wachtwoord");
    });
  }

  @Test
  void demoAccountsCanLogInWithTheirRoles() {
    assertThat(Api.login(Api.DEMO_USER, Api.DEMO_PASSWORD).getString("member.role")).isEqualTo("member");
    assertThat(Api.login(Api.ADMIN_USER, Api.DEMO_PASSWORD).getString("member.role")).isEqualTo("admin");
  }

  @Test
  void registrationNeverGrantsAdminEvenForAdminLookingIdentifiers() {
    for (String email : List.of("admin-" + Api.key().substring(0, 6) + "@pong.example",
        "BEHEER-" + Api.key().substring(0, 6) + "@Pong.Example")) {
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
          .body(Map.of("accountIdentifier", email, "credential", "geheim-wachtwoord"))
          .when().post("/v1/auth/registrations")
          .then().statusCode(201).body("role", equalTo("member"));
      String token = Api.login(email, "geheim-wachtwoord").getString("accessToken");
      Api.as(token).when().get("/v1/me").then().statusCode(200).body("role", equalTo("member"));
      Api.as(token).when().get("/v1/admin/domains").then().statusCode(403);
    }
  }

  @Test
  void meAndLogout() {
    String token = Api.newMemberToken();
    Api.as(token).when().get("/v1/me").then().statusCode(200).body("role", equalTo("member"));
    Api.as(token).when().delete("/v1/auth/sessions/current").then().statusCode(204);
    Api.as(token).when().get("/v1/me").then().statusCode(401);
  }

  @Test
  void registrationIsAudited() {
    String email = Api.uniqueEmail("audit");
    Api.register(email, "geheim-wachtwoord");
    long count = QuarkusTransaction.requiringNew().call(() ->
        AuditEventEntity.count("action = ?1 and outcome = ?2", "account.registered", "success"));
    assertThat(count).isPositive();
  }
  @Test
  void fingerprintsExcludePasswordsAndSessionReplayVerifiesCredentials() {
    String email = Api.uniqueEmail("fingerprint");
    String password = "geheim-wachtwoord";
    String registrationKey = Api.key();
    String memberId = given().contentType(ContentType.JSON).header("X-Idempotency-Key", registrationKey)
        .body(Map.of("accountIdentifier", email, "credential", password))
        .post("/v1/auth/registrations").then().statusCode(201).extract().path("id");
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", registrationKey)
        .body(Map.of("accountIdentifier", email, "credential", "ander-wachtwoord"))
        .post("/v1/auth/registrations").then().statusCode(201).body("id", equalTo(memberId));
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", registrationKey)
        .body(Map.of("accountIdentifier", Api.uniqueEmail("anders"), "credential", password))
        .post("/v1/auth/registrations").then().statusCode(409);

    String sessionKey = Api.key();
    Map<String, String> input = Map.of("accountIdentifier", email, "credential", password, "desiredRoute", "/scans");
    String token = given().contentType(ContentType.JSON).header("X-Idempotency-Key", sessionKey).body(input)
        .post("/v1/auth/sessions").then().statusCode(201).extract().path("accessToken");
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", sessionKey)
        .body(Map.of("accountIdentifier", email, "credential", "verkeerd", "desiredRoute", "/scans"))
        .post("/v1/auth/sessions").then().statusCode(401).contentType("application/problem+json");
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", sessionKey).body(input)
        .post("/v1/auth/sessions").then().statusCode(201).body("accessToken", equalTo(token));

    QuarkusTransaction.requiringNew().run(() -> {
      var member = com.example.account.domain.MemberEntity.byIdentifier(email).orElseThrow();
      var session = com.example.account.domain.SessionEntity.byIdempotencyKeyHash(
          com.example.common.Hashing.sha256(sessionKey)).orElseThrow();
      assertThat(member.registrationFingerprint).isNotEqualTo(com.example.common.Hashing.fingerprint(
          member.accountIdentifier, com.example.common.Hashing.sha256(password)));
      assertThat(session.requestFingerprint).isNotEqualTo(com.example.common.Hashing.fingerprint(
          member.accountIdentifier, com.example.common.Hashing.sha256(password), session.desiredRoute));
    });
  }

  @Test
  void limitsFailedLoginsForKnownAndUnknownIdentifiersAndResetsOnSuccess() {
    String email = Api.uniqueEmail("throttle");
    Api.register(email, "geheim-wachtwoord");
    for (int i = 0; i < 4; i++) loginStatus(email, "verkeerd", 401);
    Api.login(email.toUpperCase(java.util.Locale.ROOT), "geheim-wachtwoord");
    for (String identifier : List.of(email, Api.uniqueEmail("onbekend"))) {
      for (int i = 0; i < 5; i++) loginStatus(identifier, "verkeerd", 401);
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
          .body(Map.of("accountIdentifier", identifier, "credential", "geheim-wachtwoord"))
          .post("/v1/auth/sessions").then().statusCode(429).contentType("application/problem+json")
          .body("title", equalTo("Te veel aanmeldpogingen")).header("Retry-After", notNullValue());
    }
  }

  @Test
  void bcryptLengthLimitUsesUtf8BytesAndLoginNeverTruncates() {
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", Api.uniqueEmail("lang"), "credential", "A".repeat(129)))
        .post("/v1/auth/registrations").then().statusCode(400).contentType("application/problem+json")
        .body("errors.field", hasItem("credential"))
        .body("errors.message", hasItem("mag maximaal 128 tekens bevatten"));
    for (String password : List.of("A".repeat(73), "é".repeat(37))) {
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
          .body(Map.of("accountIdentifier", Api.uniqueEmail("lang"), "credential", password))
          .post("/v1/auth/registrations").then().statusCode(400).contentType("application/problem+json")
          .body("errors.field", hasItem("credential"))
          .body("errors.message", hasItem("Een wachtwoord mag maximaal 72 bytes zijn."));
    }
    String email = Api.uniqueEmail("grens");
    String password = "é".repeat(36);
    Api.register(email, password);
    Api.login(email, password);
    loginStatus(email, password + "A", 401);
    loginStatus(email, password + "A".repeat(50), 401);
    loginStatus(email, password + "A".repeat(100), 400);
  }

  private void loginStatus(String identifier, String credential, int status) {
    given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
        .body(Map.of("accountIdentifier", identifier, "credential", credential))
        .post("/v1/auth/sessions").then().statusCode(status).contentType("application/problem+json");
  }

  @Test
  void credentialsOver128CharactersAreRejected() {
    for (String endpoint : List.of("registrations", "sessions")) {
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key())
          .body(Map.of("accountIdentifier", Api.uniqueEmail("limiet"), "credential", "x".repeat(129)))
          .when().post("/v1/auth/" + endpoint)
          .then().statusCode(400).contentType("application/problem+json")
          .body("errors.field", hasItem("credential"));
    }
  }
}
