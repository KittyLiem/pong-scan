package com.example.content.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** Beheerde vraag binnen een kennisgebied. */
@Entity
@Table(name = "questions")
public class QuestionEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "domain_id", nullable = false) public UUID domainId;
  @Column(nullable = false) public String title;
  @Column(nullable = false) public String text;
  public String explanation;
  @Column(name = "display_order", nullable = false) public int displayOrder;
  @Column(nullable = false) public boolean active;
  @Column(name = "idempotency_key_hash", unique = true) public String idempotencyKeyHash;
  @Column(name = "request_fingerprint") public String requestFingerprint;
  @Version public long version;
}
