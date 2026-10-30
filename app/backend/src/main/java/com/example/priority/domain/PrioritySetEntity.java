package com.example.priority.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Kop van een set ontwikkelprioriteiten; (scan_id, set_revision) is uniek in de database. */
@Entity
@Immutable
@Table(name = "scan_priority_sets")
public class PrioritySetEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "set_revision", nullable = false) public int setRevision;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Column(name = "created_by") public UUID createdBy;
}
