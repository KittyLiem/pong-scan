package com.example.content.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** Beheerde antwoordoptie (score 0-5) bij een vraag. */
@Entity
@Table(name = "answer_options")
public class AnswerOptionEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "question_id", nullable = false) public UUID questionId;
  @Column(nullable = false) public int score;
  @Column(nullable = false) public String label;
  public String description;
  @Column(name = "display_order", nullable = false) public int displayOrder;
  @Column(nullable = false) public boolean available;
  @Column(name = "idempotency_key_hash", unique = true) public String idempotencyKeyHash;
  @Column(name = "request_fingerprint") public String requestFingerprint;
  @Version public long version;
}
