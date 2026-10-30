package com.example.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.support.Api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TokenVerificationTest {
  @Inject TokenService tokens;
  @Inject ObjectMapper mapper;

  private ObjectNode claimsOf(String token) throws Exception {
    return (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
  }

  private static void assertRejected(String token) {
    given().header("Authorization", "Bearer " + token)
        .when().get("/v1/me")
        .then().statusCode(401).contentType("application/problem+json").body("status", equalTo(401));
  }

  @Test
  void issuedTokenCarriesStrictClaims() throws Exception {
    String token = Api.newMemberToken();
    ObjectNode header = (ObjectNode) mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
    ObjectNode claims = claimsOf(token);
    assertThat(header.get("alg").asText()).isEqualTo("HS256");
    assertThat(header.get("typ").asText()).isEqualTo("JWT");
    assertThat(claims.get("iss").asText()).isEqualTo("pong-data-maturity-scan");
    assertThat(claims.get("aud").asText()).isEqualTo("pong-data-maturity-scan-api");
    assertThat(claims.has("sub")).isTrue();
    assertThat(claims.has("sid")).isTrue();
    assertThat(claims.get("exp").asLong()).isGreaterThan(claims.get("iat").asLong());
    given().header("Authorization", "Bearer " + token).when().get("/v1/me").then().statusCode(200);
  }

  @Test
  void wrongIssuerIsRejected() throws Exception {
    ObjectNode claims = claimsOf(Api.newMemberToken());
    claims.put("iss", "iemand-anders");
    assertRejected(tokens.sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims.toString()));
  }

  @Test
  void wrongAudienceIsRejected() throws Exception {
    ObjectNode claims = claimsOf(Api.newMemberToken());
    claims.put("aud", "andere-api");
    assertRejected(tokens.sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims.toString()));
  }

  @Test
  void wrongAlgorithmHeaderIsRejected() throws Exception {
    String token = Api.newMemberToken();
    ObjectNode claims = claimsOf(token);
    // Zelfde claims en geldige HMAC, maar header met ander algoritme.
    assertRejected(tokens.sign("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", claims.toString()));
    // "none"-algoritme zonder handtekening.
    String unsigned = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
        + "." + token.split("\\.")[1] + ".";
    assertRejected(unsigned);
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    ObjectNode claims = claimsOf(Api.newMemberToken());
    long past = Instant.now().minusSeconds(7200).getEpochSecond();
    claims.put("iat", past);
    claims.put("exp", past + 60);
    assertRejected(tokens.sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims.toString()));
  }

  @Test
  void futureIssuedAtIsRejected() throws Exception {
    ObjectNode claims = claimsOf(Api.newMemberToken());
    long future = Instant.now().plusSeconds(3600).getEpochSecond();
    claims.put("iat", future);
    claims.put("exp", future + 3600);
    assertRejected(tokens.sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims.toString()));
  }

  @Test
  void tamperedSignatureIsRejected() {
    String token = Api.newMemberToken();
    String[] parts = token.split("\\.");
    String otherSignature = Api.newMemberToken().split("\\.")[2];
    assertRejected(parts[0] + "." + parts[1] + "." + otherSignature);
  }

  @Test
  void revokedSessionIsRejected() {
    String token = Api.newMemberToken();
    given().header("Authorization", "Bearer " + token).when().delete("/v1/auth/sessions/current")
        .then().statusCode(204);
    assertRejected(token);
  }
}
