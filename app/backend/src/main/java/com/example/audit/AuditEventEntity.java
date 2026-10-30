package com.example.audit;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Append-only audit-event. Bevat nooit credentials of tokens. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEventEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "occurred_at", nullable = false) public OffsetDateTime occurredAt;
  @Column(name = "actor_member_id") public UUID actorMemberId;
  @Column(nullable = false) public String action;
  @Column(name = "resource_type", nullable = false) public String resourceType;
  @Column(name = "resource_id") public UUID resourceId;
  @Column(name = "organization_id") public UUID organizationId;
  @Column(name = "correlation_id") public String correlationId;
  @Column(nullable = false) public String outcome;
}
