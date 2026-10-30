package com.example.results.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ResultsDtos {
  private ResultsDtos() {}

  public record MaturityLevel(int level, String label) {}

  public record DomainScore(UUID domainId, String domainName, double score, MaturityLevel maturityLevel) {}

  public record DevelopmentPoint(
      UUID id, UUID domainId, String domainName, String classification, String text, String recommendation) {}

  public record OrganizationGoal(String name, String priority, String customText) {}

  public record PriorityItem(UUID developmentPointId, String impact, String urgency, boolean selected) {}

  public record ActionOverview(int setRevision, List<PriorityItem> items) {}

  public record ScanResults(
      UUID scanId,
      String scanName,
      String assessmentPeriod,
      OffsetDateTime completedAt,
      double totalScore,
      MaturityLevel maturityLevel,
      List<DomainScore> domainScores,
      List<DevelopmentPoint> developmentPoints,
      List<OrganizationGoal> goals,
      ActionOverview priorities,
      List<Integer> availablePrioritySetRevisions) {}
}
