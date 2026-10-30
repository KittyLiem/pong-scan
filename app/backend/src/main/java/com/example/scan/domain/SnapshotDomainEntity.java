package com.example.scan.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable kopie van een beheerd domein op het moment van aanmaken van de scan. */
@Entity
@Immutable
@Table(name = "scan_snapshot_domains")
public class SnapshotDomainEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "source_id", nullable = false) public UUID sourceId;
  @Column(nullable = false) public String name;
  public String description;
  @Column(name = "display_order", nullable = false) public int displayOrder;
}
