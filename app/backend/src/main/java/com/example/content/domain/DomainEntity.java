package com.example.content.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** Beheerd kennisgebied (bron voor nieuwe scans). */
@Entity
@Table(name = "domains")
public class DomainEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(nullable = false) public String name;
  public String description;
  @Column(name = "display_order", nullable = false) public int displayOrder;
  @Column(nullable = false) public boolean active;
  @Column(nullable = false) public boolean required;
  @Column(name = "idempotency_key_hash", unique = true) public String idempotencyKeyHash;
  @Column(name = "request_fingerprint") public String requestFingerprint;
  @Version public long version;
}
