package com.example.priority.application;

import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Problem;
import com.example.priority.api.DevelopmentPriorityDtos;
import com.example.priority.domain.DevelopmentPriorityEntity;
import com.example.priority.domain.PrioritySetEntity;
import com.example.results.api.ResultsDtos;
import com.example.results.application.Recommendations;
import com.example.results.application.ResultsService;
import com.example.scan.application.ScanAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Opslaan voegt een nieuwe set (set_revision + 1) toe; eerdere sets blijven ongewijzigd bewaard. */
@ApplicationScoped
public class DevelopmentPriorityService {
  private final ResultsService results;
  private final AuditService audit;
  private final ScanAccess access;

  public DevelopmentPriorityService(ResultsService results, AuditService audit, ScanAccess access) {
    this.results = results;
    this.audit = audit;
    this.access = access;
  }

  @Transactional
  public ResultsDtos.ActionOverview save(UUID memberId, UUID scanId, DevelopmentPriorityDtos.PrioritiesInput input) {
    // Lock op de scanrij serialiseert het toekennen van set_revision; daarnaast is (scan_id, set_revision) uniek.
    access.lock(memberId, scanId);
    ResultsService.Computed computed = results.compute(memberId, scanId);
    // Alleen ontwikkelpunten en gaps zijn te prioriteren; sterktes niet.
    Set<UUID> known = new HashSet<>();
    computed.results().developmentPoints().stream()
        .filter(point -> !Recommendations.STRENGTH.equals(point.classification()))
        .forEach(point -> known.add(point.id()));
    Set<UUID> seen = new HashSet<>();
    for (DevelopmentPriorityDtos.PriorityInput item : input.items()) {
      if (!known.contains(item.developmentPointId())) {
        throw new ApiException(422, "Onbekend ontwikkelpunt", "Een of meer ontwikkelpunten horen niet bij deze scan of zijn een sterkte (niet te prioriteren).",
            List.of(new Problem.FieldError("items", "bevat een onbekend ontwikkelpunt")));
      }
      if (!seen.add(item.developmentPointId())) {
        throw new ApiException(422, "Dubbel ontwikkelpunt", "Ieder ontwikkelpunt mag maar één keer voorkomen.",
            List.of(new Problem.FieldError("items", "bevat een ontwikkelpunt meer dan eens")));
      }
    }
    int revision = DevelopmentPriorityEntity.latestRevision(scanId) + 1;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    PrioritySetEntity set = new PrioritySetEntity();
    set.id = UUID.randomUUID();
    set.scanId = scanId;
    set.setRevision = revision;
    set.createdAt = now;
    set.createdBy = memberId;
    set.persist();
    PrioritySetEntity.getEntityManager().flush();
    for (DevelopmentPriorityDtos.PriorityInput item : input.items()) {
      DevelopmentPriorityEntity entity = new DevelopmentPriorityEntity();
      entity.id = UUID.randomUUID();
      entity.scanId = scanId;
      entity.setRevision = revision;
      entity.developmentPointId = item.developmentPointId();
      entity.impact = item.impact();
      entity.urgency = item.urgency();
      entity.selected = item.selected();
      entity.createdAt = now;
      entity.createdBy = memberId;
      entity.persist();
    }
    DevelopmentPriorityEntity.getEntityManager().flush();
    audit.success(memberId, "priorities.save", "scan", scanId, computed.scan().organizationId);
    return new ResultsDtos.ActionOverview(revision, input.items().stream()
        .map(item -> new ResultsDtos.PriorityItem(item.developmentPointId(), item.impact(), item.urgency(),
            item.selected()))
        .toList());
  }
}
