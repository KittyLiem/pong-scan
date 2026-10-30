package com.example.scan.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "scans")
public class ScanEntity extends PanacheEntityBase {
  public static final String CONCEPT = "concept";
  public static final String IN_PROGRESS = "in-progress";
  public static final String CONTENT_COMPLETE = "content-complete";
  public static final String COMPLETED = "completed";

  @Id public UUID id;
  @Column(name = "organization_id", nullable = false) public UUID organizationId;
  @Column(nullable = false) public String name;
  @Column(name = "assessment_period", nullable = false) public String assessmentPeriod;
  @Column(nullable = false) public String status;
  @Column(nullable = false) public int progress;
  @Column(nullable = false) public int revision = 1;
  @Column(name = "revision_of") public UUID revisionOf;
  @Column(name = "general_comments") public String generalComments;
  @Column(name = "idempotency_key_hash") public String idempotencyKeyHash;
  @Column(name = "request_fingerprint") public String requestFingerprint;
  @Column(name = "completion_idempotency_key_hash") public String completionIdempotencyKeyHash;
  @Column(name = "completion_request_fingerprint") public String completionRequestFingerprint;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Column(name = "updated_at", nullable = false) public OffsetDateTime updatedAt;
  @Column(name = "completed_at") public OffsetDateTime completedAt;
  @Version public long version;

  /** Geselecteerde snapshot-domeinen (FK naar scan_snapshot_domains). */
  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "scan_selected_domains", joinColumns = @JoinColumn(name = "scan_id"))
  @Column(name = "snapshot_domain_id", nullable = false)
  @BatchSize(size = 100)
  public Set<UUID> selectedDomainIds = new HashSet<>();

  @ElementCollection(fetch = FetchType.LAZY)
  @CollectionTable(name = "scan_goals", joinColumns = @JoinColumn(name = "scan_id"))
  @OrderColumn(name = "position")
  @BatchSize(size = 100)
  public List<Goal> goals = new ArrayList<>();

  public boolean isCompleted() {
    return COMPLETED.equals(status);
  }

  public static Optional<ScanEntity> byOrganizationAndIdempotencyKey(UUID organizationId, String keyHash) {
    return find("organizationId = ?1 and idempotencyKeyHash = ?2", organizationId, keyHash).firstResultOptional();
  }

  @Embeddable
  public static class Goal {
    @Column(name = "goal_name", nullable = false) public String name;
    @Column(name = "priority", nullable = false) public String priority;
    @Column(name = "custom_text") public String customText;

    public Goal() {}

    public Goal(String name, String priority, String customText) {
      this.name = name;
      this.priority = priority;
      this.customText = customText;
    }
  }
}
