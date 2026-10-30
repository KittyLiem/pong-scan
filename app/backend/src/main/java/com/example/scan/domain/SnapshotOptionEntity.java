package com.example.scan.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable kopie van een beheerde antwoordoptie inclusief score. */
@Entity
@Immutable
@Table(name = "scan_snapshot_options")
public class SnapshotOptionEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "snapshot_question_id", nullable = false) public UUID snapshotQuestionId;
  @Column(name = "source_id", nullable = false) public UUID sourceId;
  @Column(nullable = false) public int score;
  @Column(nullable = false) public String label;
  public String description;
  @Column(name = "display_order", nullable = false) public int displayOrder;
}
