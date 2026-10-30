package com.example.content.application;

import com.example.content.domain.AnswerOptionEntity;
import com.example.content.domain.DomainEntity;
import com.example.content.domain.QuestionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bepaalt de actieve inhoud: actieve domeinen met ten minste één actieve vraag die ten minste één beschikbare
 * optie heeft. Alleen deze inhoud wordt in een scan-snapshot opgenomen. Drie queries, geen N+1.
 */
@ApplicationScoped
public class ActiveContentService {


  public record ActiveQuestion(QuestionEntity question, List<AnswerOptionEntity> options) {}

  public record ActiveDomain(DomainEntity domain, List<ActiveQuestion> questions) {}

  @Transactional
  public List<ActiveDomain> load() {
    List<DomainEntity> domains = DomainEntity.list("active = true order by displayOrder, id");
    if (domains.isEmpty()) return List.of();
    List<UUID> domainIds = domains.stream().map(domain -> domain.id).toList();
    List<QuestionEntity> questions = QuestionEntity.list(
        "active = true and domainId in ?1 order by displayOrder, id", domainIds);
    if (questions.isEmpty()) return List.of();
    List<UUID> questionIds = questions.stream().map(question -> question.id).toList();
    List<AnswerOptionEntity> options = AnswerOptionEntity.list(
        "available = true and questionId in ?1 order by displayOrder, score, id", questionIds);

    Map<UUID, List<AnswerOptionEntity>> optionsByQuestion = new LinkedHashMap<>();
    options.forEach(option -> optionsByQuestion.computeIfAbsent(option.questionId, key -> new ArrayList<>()).add(option));
    Map<UUID, List<ActiveQuestion>> questionsByDomain = new LinkedHashMap<>();
    for (QuestionEntity question : questions) {
      List<AnswerOptionEntity> questionOptions = optionsByQuestion.get(question.id);
      if (questionOptions == null || questionOptions.isEmpty()) continue;
      questionsByDomain.computeIfAbsent(question.domainId, key -> new ArrayList<>())
          .add(new ActiveQuestion(question, List.copyOf(questionOptions)));
    }
    List<ActiveDomain> result = new ArrayList<>();
    for (DomainEntity domain : domains) {
      List<ActiveQuestion> domainQuestions = questionsByDomain.get(domain.id);
      if (domainQuestions == null || domainQuestions.isEmpty()) continue;
      domainQuestions.sort(Comparator.comparingInt((ActiveQuestion item) -> item.question().displayOrder));
      result.add(new ActiveDomain(domain, List.copyOf(domainQuestions)));
    }
    return result;
  }
}
