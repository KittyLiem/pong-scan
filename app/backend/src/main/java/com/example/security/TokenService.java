package com.example.security;

import com.example.account.domain.MemberEntity;
import com.example.account.domain.SessionEntity;
import com.example.common.ApiException;
import com.example.common.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Uitgifte en strikte verificatie van HS256-tokens: header {@code alg}=HS256 en {@code typ}=JWT, claims
 * {@code iss}, {@code aud}, {@code sub}, {@code sid}, {@code iat} (max. 60s in de toekomst) en {@code exp}.
 * Daarnaast moet de sessie (via token-hash) bestaan, niet verlopen en niet ingetrokken zijn.
 */
@ApplicationScoped
public class TokenService {
  static final long CLOCK_SKEW_SECONDS = 60;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final ObjectMapper objectMapper;
  private final String secret;
  private final String issuer;
  private final String audience;
  private final Duration ttl;

  public TokenService(
      ObjectMapper objectMapper,
      @ConfigProperty(name = "account.token.secret") String secret,
      @ConfigProperty(name = "account.token.issuer", defaultValue = "pong-data-maturity-scan") String issuer,
      @ConfigProperty(name = "account.token.audience", defaultValue = "pong-data-maturity-scan-api") String audience,
      @ConfigProperty(name = "account.token.ttl", defaultValue = "PT1H") Duration ttl) {
    this.objectMapper = objectMapper;
    this.secret = secret;
    this.issuer = issuer;
    this.audience = audience;
    this.ttl = ttl;
  }

  public Duration ttl() {
    return ttl;
  }

  public record Verified(UUID memberId, UUID sessionId, String role, String accountIdentifier) {}

  /** Deterministisch: dezelfde sessie levert hetzelfde token op (nodig voor idempotente replay). */
  public String issue(SessionEntity session) {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "HS256");
    header.put("typ", "JWT");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", audience);
    claims.put("sub", session.memberId.toString());
    claims.put("sid", session.id.toString());
    claims.put("iat", session.issuedAt.getEpochSecond());
    claims.put("exp", session.expiresAt.getEpochSecond());
    return sign(json(header), json(claims));
  }

  /** Bouwt een token met willekeurige header/claims (voor tests van de verificatie). */
  public String sign(String headerJson, String claimsJson) {
    String unsigned = ENCODER.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
        + ENCODER.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
    return unsigned + "." + ENCODER.encodeToString(hmac(unsigned));
  }

  @Transactional
  public Verified verify(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw ApiException.unauthorized("Aanmelden is vereist: stuur een geldig bearer-token mee.");
    }
    String token = authorization.substring(7).trim();
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
      throw invalid();
    }
    Instant now = Instant.now();
    UUID memberId;
    UUID sessionId;
    try {
      JsonNode header = objectMapper.readTree(DECODER.decode(parts[0]));
      if (!header.isObject() || !"HS256".equals(header.path("alg").asText(null))
          || !"JWT".equals(header.path("typ").asText(null))) {
        throw invalid();
      }
      byte[] signature = DECODER.decode(parts[2]);
      if (!MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), signature)) {
        throw invalid();
      }
      JsonNode claims = objectMapper.readTree(DECODER.decode(parts[1]));
      if (!claims.isObject()
          || !claims.path("iss").isTextual() || !issuer.equals(claims.get("iss").asText())
          || !claims.path("aud").isTextual() || !audience.equals(claims.get("aud").asText())
          || !claims.path("iat").isIntegralNumber() || !claims.path("exp").isIntegralNumber()
          || !claims.path("sub").isTextual() || !claims.path("sid").isTextual()) {
        throw invalid();
      }
      long iat = claims.get("iat").asLong();
      long exp = claims.get("exp").asLong();
      if (iat > now.getEpochSecond() + CLOCK_SKEW_SECONDS || exp <= iat) {
        throw invalid();
      }
      if (exp <= now.getEpochSecond()) {
        throw ApiException.unauthorized("Uw sessie is verlopen. Meld u opnieuw aan.");
      }
      memberId = UUID.fromString(claims.get("sub").asText());
      sessionId = UUID.fromString(claims.get("sid").asText());
    } catch (ApiException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid();
    }

    SessionEntity session = SessionEntity.<SessionEntity>findByIdOptional(sessionId).orElseThrow(TokenService::invalid);
    if (!session.memberId.equals(memberId)
        || !Hashing.constantTimeEquals(session.accessTokenHash, Hashing.sha256(token))) {
      throw invalid();
    }
    if (session.revokedAt != null) {
      throw ApiException.unauthorized("Deze sessie is beëindigd. Meld u opnieuw aan.");
    }
    if (!session.expiresAt.isAfter(now)) {
      throw ApiException.unauthorized("Uw sessie is verlopen. Meld u opnieuw aan.");
    }
    MemberEntity member = MemberEntity.<MemberEntity>findByIdOptional(memberId).orElseThrow(TokenService::invalid);
    return new Verified(member.id, session.id, member.role, member.accountIdentifier);
  }

  private static ApiException invalid() {
    return ApiException.unauthorized("Het bearer-token is ongeldig.");
  }

  private byte[] hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("HMAC-SHA256 is niet beschikbaar", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Token kon niet worden opgebouwd", exception);
    }
  }
}
