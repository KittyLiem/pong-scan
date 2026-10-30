package com.example.scan.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

/** Antwoord op een snapshot-vraag; de optie hoort via een samengestelde FK bij dezelfde vraag. */
@Entity
@Table(name = "scan_answers")
public class AnswerEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "question_id", nullable = false) public UUID questionId;
  @Column(name = "option_id", nullable = false) public UUID optionId;
  public String explanation;
  @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;
  @Version public long version;

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "scan_answer_evidence", joinColumns = @JoinColumn(name = "answer_id"))
  @Column(name = "evidence_reference", nullable = false, length = 2048)
  @OrderColumn(name = "position")
  @BatchSize(size = 100)
  public List<String> evidenceReferences = new ArrayList<>();

  public static Optional<AnswerEntity> byScanAndQuestion(UUID scanId, UUID questionId) {
    return find("scanId = ?1 and questionId = ?2", scanId, questionId).firstResultOptional();
  }

  /** API-versie: 0 betekent "nog geen antwoord", daarna persistence-versie + 1. */
  public long apiVersion() {
    return version + 1;
  }
}
