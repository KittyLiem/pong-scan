package com.example.account.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "account_sessions")
public class SessionEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "member_id", nullable = false) public UUID memberId;
  @Column(name = "access_token_hash", nullable = false, unique = true) public String accessTokenHash;
  @Column(name = "idempotency_key_hash", nullable = false, unique = true) public String idempotencyKeyHash;
  @Column(name = "request_fingerprint", nullable = false) public String requestFingerprint;
  @Column(name = "desired_route") public String desiredRoute;
  @Column(name = "issued_at", nullable = false) public Instant issuedAt;
  @Column(name = "expires_at", nullable = false) public Instant expiresAt;
  @Column(name = "revoked_at") public Instant revokedAt;
  @Version public long version;

  public static Optional<SessionEntity> byIdempotencyKeyHash(String keyHash) {
    return find("idempotencyKeyHash", keyHash).firstResultOptional();
  }
}
