package com.example.priority.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Eén regel in een geversioneerde set ontwikkelprioriteiten. Sets worden nooit gewijzigd of verwijderd. */
@Entity
@Immutable
@Table(name = "scan_development_priorities")
public class DevelopmentPriorityEntity extends PanacheEntityBase {
  @Id public UUID id;
  @Column(name = "scan_id", nullable = false) public UUID scanId;
  @Column(name = "set_revision", nullable = false) public int setRevision;
  @Column(name = "development_point_id", nullable = false) public UUID developmentPointId;
  @Column(nullable = false) public String impact;
  @Column(nullable = false) public String urgency;
  @Column(nullable = false) public boolean selected;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Column(name = "created_by") public UUID createdBy;

  public static int latestRevision(UUID scanId) {
    Integer max = getEntityManager()
        .createQuery("select max(p.setRevision) from PrioritySetEntity p where p.scanId = :scanId",
            Integer.class)
        .setParameter("scanId", scanId)
        .getSingleResult();
    return max == null ? 0 : max;
  }

  public static List<DevelopmentPriorityEntity> set(UUID scanId, int setRevision) {
    return list("scanId = ?1 and setRevision = ?2 order by createdAt, id", scanId, setRevision);
  }
}
