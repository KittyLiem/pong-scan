package com.example.scan.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable kopie van een beheerde vraag. */
@Entity
@Immutable
@Table(name = "scan_snapshot_questions")
public class SnapshotQuestionEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "snapshot_domain_id", nullable = false) public UUID snapshotDomainId;
  @Column(name = "source_id", nullable = false) public UUID sourceId;
  @Column(nullable = false) public String title;
  @Column(nullable = false) public String text;
  public String explanation;
  @Column(name = "display_order", nullable = false) public int displayOrder;
}
