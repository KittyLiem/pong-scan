package com.example.account.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "account_members")
public class MemberEntity extends PanacheEntityBase {
  public static final String ROLE_MEMBER = "member";
  public static final String ROLE_ADMIN = "admin";

  @Id public UUID id;
  @Column(name = "account_identifier", nullable = false, unique = true) public String accountIdentifier;
  @Column(name = "credential_hash", nullable = false) public String credentialHash;
  @Column(nullable = false) public String role = ROLE_MEMBER;
  @Column(name = "registration_key_hash", unique = true) public String registrationKeyHash;
  @Column(name = "registration_fingerprint") public String registrationFingerprint;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Column(name = "failed_login_count", nullable = false) public int failedLoginCount;
  @Column(name = "failed_login_window_ends") public OffsetDateTime failedLoginWindowEnds;
  @Version public long version;

  public static Optional<MemberEntity> byIdentifier(String identifier) {
    return find("accountIdentifier", identifier).firstResultOptional();
  }

  public static Optional<MemberEntity> byRegistrationKeyHash(String keyHash) {
    return find("registrationKeyHash", keyHash).firstResultOptional();
  }
}
