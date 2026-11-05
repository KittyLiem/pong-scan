package com.example.organization.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Lidmaatschap van een account bij een organisatie; bepaalt de toegang tot de organisatie en haar scans. */
@Entity
@Table(name = "organization_members")
@IdClass(OrganizationMemberEntity.Key.class)
public class OrganizationMemberEntity extends PanacheEntityBase {
  public static final String OWNER = "owner";

  @Id @Column(name = "organization_id") public UUID organizationId;
  @Id @Column(name = "member_id") public UUID memberId;
  @Column(nullable = false) public String role;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;

  public static class Key implements Serializable {
    public UUID organizationId;
    public UUID memberId;

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key && Objects.equals(organizationId, key.organizationId)
          && Objects.equals(memberId, key.memberId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(organizationId, memberId);
    }
  }
}
