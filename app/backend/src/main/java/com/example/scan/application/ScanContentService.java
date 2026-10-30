package com.example.scan.application;

import com.example.scan.api.ScanDtos;
import com.example.scan.domain.AnswerEntity;
import com.example.scan.domain.ScanEntity;
import com.example.scan.domain.SnapshotDomainEntity;
import com.example.scan.domain.SnapshotOptionEntity;
import com.example.scan.domain.SnapshotQuestionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Levert de snapshot-inhoud van een scan met antwoorden. Vaste set queries (geen N+1). */
@ApplicationScoped
public class ScanContentService {
  private final ScanAccess access;

  public ScanContentService(ScanAccess access) {
    this.access = access;
  }

  public record Snapshot(
      List<SnapshotDomainEntity> domains,
      Map<UUID, List<SnapshotQuestionEntity>> questionsByDomain,
      Map<UUID, List<SnapshotOptionEntity>> optionsByQuestion,
      Map<UUID, AnswerEntity> answersByQuestion) {

    public SnapshotOptionEntity option(UUID questionId, UUID optionId) {
      return optionsByQuestion.getOrDefault(questionId, List.of()).stream()
          .filter(option -> option.id.equals(optionId)).findFirst().orElse(null);
    }
  }

  /** Laadt de volledige snapshot van een scan in vier queries. */
  public static Snapshot loadSnapshot(UUID scanId) {
    List<SnapshotDomainEntity> domains = SnapshotDomainEntity.list("scanId = ?1 order by displayOrder, id", scanId);
    List<SnapshotQuestionEntity> questions = SnapshotQuestionEntity.list(
        "scanId = ?1 order by displayOrder, id", scanId);
    List<SnapshotOptionEntity> options = SnapshotOptionEntity.list(
        "scanId = ?1 order by displayOrder, score, id", scanId);
    List<AnswerEntity> answers = AnswerEntity.find(
        "select distinct a from AnswerEntity a left join fetch a.evidenceReferences where a.scanId = ?1", scanId)
        .list();
    Map<UUID, List<SnapshotQuestionEntity>> questionsByDomain = new HashMap<>();
    questions.forEach(question ->
        questionsByDomain.computeIfAbsent(question.snapshotDomainId, key -> new ArrayList<>()).add(question));
    Map<UUID, List<SnapshotOptionEntity>> optionsByQuestion = new HashMap<>();
    options.forEach(option ->
        optionsByQuestion.computeIfAbsent(option.snapshotQuestionId, key -> new ArrayList<>()).add(option));
    Map<UUID, AnswerEntity> answersByQuestion = new HashMap<>();
    answers.forEach(answer -> answersByQuestion.put(answer.questionId, answer));
    return new Snapshot(domains, questionsByDomain, optionsByQuestion, answersByQuestion);
  }

  @Transactional
  public ScanDtos.ScanContent content(UUID memberId, UUID scanId) {
    ScanEntity scan = access.load(memberId, scanId);
    Snapshot snapshot = loadSnapshot(scan.id);
    List<ScanDtos.ContentDomain> domains = new ArrayList<>();
    for (SnapshotDomainEntity domain : snapshot.domains()) {
      List<ScanDtos.ContentQuestion> questions = new ArrayList<>();
      int answered = 0;
      for (SnapshotQuestionEntity question : snapshot.questionsByDomain().getOrDefault(domain.id, List.of())) {
        List<ScanDtos.ContentOption> options = snapshot.optionsByQuestion().getOrDefault(question.id, List.of())
            .stream()
            .map(option -> new ScanDtos.ContentOption(option.id, option.label, option.description, option.score,
                option.displayOrder))
            .toList();
        AnswerEntity answer = snapshot.answersByQuestion().get(question.id);
        ScanDtos.ContentAnswer contentAnswer = null;
        if (answer != null) {
          answered++;
          contentAnswer = new ScanDtos.ContentAnswer(answer.optionId, answer.explanation,
              List.copyOf(answer.evidenceReferences), answer.apiVersion());
        }
        questions.add(new ScanDtos.ContentQuestion(question.id, question.title, question.text,
            question.explanation, question.displayOrder, options, contentAnswer));
      }
      domains.add(new ScanDtos.ContentDomain(domain.id, domain.name, domain.description, domain.displayOrder,
          scan.selectedDomainIds.contains(domain.id), questions.size(), answered, questions));
    }
    return new ScanDtos.ScanContent(scan.id, scan.status, !scan.isCompleted(), domains);
  }
}
