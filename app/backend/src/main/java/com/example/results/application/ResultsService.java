package com.example.results.application;

import com.example.common.ApiException;
import com.example.priority.domain.DevelopmentPriorityEntity;
import com.example.priority.domain.PrioritySetEntity;
import com.example.results.api.ResultsDtos;
import com.example.scan.application.ScanAccess;
import com.example.scan.application.ScanContentService;
import com.example.scan.domain.AnswerEntity;
import com.example.scan.domain.ScanEntity;
import com.example.scan.domain.SnapshotDomainEntity;
import com.example.scan.domain.SnapshotOptionEntity;
import com.example.scan.domain.SnapshotQuestionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resultaten op basis van uitsluitend de snapshot van een afgeronde scan.
 * <ul>
 *   <li>Domeinscore = gemiddelde score van de beantwoorde vragen in dat (geselecteerde) domein.</li>
 *   <li>Totaalscore = gemiddelde van alle beantwoorde vragen in de geselecteerde domeinen (dus gewogen naar
 *       aantal vragen, niet het gemiddelde van domeingemiddelden).</li>
 *   <li>Niveau = afgeronde score naar beneden (0-5) met Nederlands label.</li>
 * </ul>
 * Doelen en prioriteiten worden alleen getoond en beïnvloeden de scores nooit.
 */
@ApplicationScoped
public class ResultsService {
  private final ScanAccess access;

  public ResultsService(ScanAccess access) {
    this.access = access;
  }

  public record Computed(ScanEntity scan, ResultsDtos.ScanResults results) {}

  @Transactional
  public ResultsDtos.ScanResults get(UUID memberId, UUID scanId) {
    return get(memberId, scanId, null);
  }

  @Transactional
  public ResultsDtos.ScanResults get(UUID memberId, UUID scanId, Integer prioritySetRevision) {
    return compute(memberId, scanId, prioritySetRevision).results();
  }

  @Transactional
  public Computed compute(UUID memberId, UUID scanId) {
    return compute(memberId, scanId, null);
  }

  @Transactional
  public Computed compute(UUID memberId, UUID scanId, Integer prioritySetRevision) {
    ScanEntity scan = access.load(memberId, scanId);
    if (!scan.isCompleted()) {
      throw ApiException.conflict("Resultaten nog niet beschikbaar",
          "Resultaten zijn beschikbaar zodra de scan is afgerond.");
    }
    ScanContentService.Snapshot snapshot = ScanContentService.loadSnapshot(scan.id);
    List<ResultsDtos.DomainScore> domainScores = new ArrayList<>();
    List<ResultsDtos.DevelopmentPoint> points = new ArrayList<>();
    int totalSum = 0;
    int totalCount = 0;
    for (SnapshotDomainEntity domain : snapshot.domains()) {
      if (!scan.selectedDomainIds.contains(domain.id)) continue;
      int sum = 0;
      int count = 0;
      for (SnapshotQuestionEntity question : snapshot.questionsByDomain().getOrDefault(domain.id, List.of())) {
        AnswerEntity answer = snapshot.answersByQuestion().get(question.id);
        if (answer == null) continue;
        SnapshotOptionEntity option = snapshot.option(question.id, answer.optionId);
        if (option == null) continue;
        sum += option.score;
        count++;
      }
      if (count == 0) continue;
      totalSum += sum;
      totalCount += count;
      double score = round((double) sum / count);
      domainScores.add(new ResultsDtos.DomainScore(domain.id, domain.name, score, level(score)));
      String classification = Recommendations.classify(score);
      points.add(new ResultsDtos.DevelopmentPoint(developmentPointId(scan.id, domain.id, classification),
          domain.id, domain.name, classification, Recommendations.text(domain.name, score, classification),
          Recommendations.recommendation(domain.name, classification)));
    }
    double total = totalCount == 0 ? 0 : round((double) totalSum / totalCount);
    List<ResultsDtos.OrganizationGoal> goals = scan.goals.stream()
        .map(goal -> new ResultsDtos.OrganizationGoal(goal.name, goal.priority, goal.customText)).toList();
    ResultsDtos.ScanResults results = new ResultsDtos.ScanResults(scan.id, scan.name, scan.assessmentPeriod,
        scan.completedAt, total, level(total), domainScores, points, goals, priorities(scan.id, prioritySetRevision), availableRevisions(scan.id));
    return new Computed(scan, results);
  }

  public static ResultsDtos.ActionOverview latestPriorities(UUID scanId) {
    return priorities(scanId, null);
  }

  private static List<Integer> availableRevisions(UUID scanId) {
    return PrioritySetEntity.<PrioritySetEntity>list("scanId = ?1 order by setRevision", scanId)
        .stream().map(set -> set.setRevision).toList();
  }

  private static ResultsDtos.ActionOverview priorities(UUID scanId, Integer requestedRevision) {
    List<Integer> revisions = availableRevisions(scanId);
    if (requestedRevision != null && !revisions.contains(requestedRevision)) {
      throw ApiException.notFound("De gevraagde versie van de prioriteiten bestaat niet voor deze scan.");
    }
    int revision = requestedRevision != null ? requestedRevision
        : revisions.isEmpty() ? 0 : revisions.getLast();
    if (revision == 0) return new ResultsDtos.ActionOverview(0, List.of());
    return new ResultsDtos.ActionOverview(revision, DevelopmentPriorityEntity.set(scanId, revision).stream()
        .map(item -> new ResultsDtos.PriorityItem(item.developmentPointId, item.impact, item.urgency, item.selected))
        .toList());
  }

  static UUID developmentPointId(UUID scanId, UUID domainId, String classification) {
    return UUID.nameUUIDFromBytes((scanId + ":" + domainId + ":" + classification).getBytes(StandardCharsets.UTF_8));
  }

  private static ResultsDtos.MaturityLevel level(double score) {
    int level = Recommendations.level(score);
    return new ResultsDtos.MaturityLevel(level, Recommendations.label(level));
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }
}
