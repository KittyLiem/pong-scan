package com.example.organization.domain;

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
@Table(name = "organizations")
public class OrganizationEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "member_id", nullable = false) public UUID memberId;
  @Column(nullable = false) public String name;
  @Column(name = "chamber_of_commerce_number") public String chamberOfCommerceNumber;
  public String industry;
  @Column(name = "additional_information") public String additionalInformation;
  @Column(nullable = false) public String status;
  @Column(name = "idempotency_key_hash", nullable = false) public String idempotencyKeyHash;
  @Column(name = "request_fingerprint", nullable = false) public String requestFingerprint;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Version public long version;

  public static Optional<OrganizationEntity> byMemberAndIdempotencyKey(UUID memberId, String keyHash) {
    return find("memberId = ?1 and idempotencyKeyHash = ?2", memberId, keyHash).firstResultOptional();
  }

  public static boolean accessible(UUID memberId, UUID organizationId) {
    return count("id = ?1 and memberId = ?2", organizationId, memberId) > 0;
  }
}
