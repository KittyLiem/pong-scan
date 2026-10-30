package com.example.scan.application;

import com.example.common.ApiException;
import com.example.common.Texts;
import com.example.scan.api.ScanDtos;
import com.example.scan.domain.AnswerEntity;
import com.example.scan.domain.ScanEntity;
import com.example.scan.domain.SnapshotOptionEntity;
import com.example.scan.domain.SnapshotQuestionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AnswerService {
  private final ScanAccess access;
  private final ScanState state;

  public AnswerService(ScanAccess access, ScanState state) {
    this.access = access;
    this.state = state;
  }

  @Transactional
  public ScanDtos.SavedAnswer save(UUID memberId, UUID scanId, UUID questionId, ScanDtos.AnswerInput input) {
    ScanEntity scan = access.lock(memberId, scanId);
    if (scan.isCompleted()) {
      throw ApiException.conflict("Scan is afgerond",
          "Antwoorden van een afgeronde scan kunnen niet meer worden gewijzigd.");
    }
    SnapshotQuestionEntity question = SnapshotQuestionEntity.<SnapshotQuestionEntity>find(
            "id = ?1 and scanId = ?2", questionId, scan.id)
        .firstResultOptional()
        .orElseThrow(() -> ApiException.notFound("Vraag niet gevonden in deze scan."));
    if (!scan.selectedDomainIds.contains(question.snapshotDomainId)) {
      throw ApiException.unprocessable("Vraag niet geselecteerd",
          "Deze vraag hoort bij een kennisgebied dat niet is geselecteerd.");
    }
    SnapshotOptionEntity option = SnapshotOptionEntity.findById(input.optionId());
    if (option == null || !option.snapshotQuestionId.equals(question.id)) {
      throw ApiException.unprocessable("Ongeldige antwoordoptie", "De gekozen antwoordoptie hoort niet bij deze vraag.");
    }

    AnswerEntity answer = AnswerEntity.byScanAndQuestion(scan.id, question.id).orElse(null);
    if (answer == null) {
      if (input.version() != 0) throw ApiException.versionConflict();
      answer = new AnswerEntity();
      answer.id = UUID.randomUUID();
      answer.scanId = scan.id;
      answer.questionId = question.id;
    } else if (answer.apiVersion() != input.version()) {
      throw ApiException.versionConflict();
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    answer.optionId = option.id;
    answer.explanation = Texts.blankToNull(input.explanation());
    answer.evidenceReferences.clear();
    if (input.evidenceReferences() != null) {
      input.evidenceReferences().stream().map(String::trim).forEach(answer.evidenceReferences::add);
    }
    answer.updatedAt = now;
    answer.persist();
    Panache.getEntityManager().flush();

    // Status/voortgang bijwerken zonder de scanversie op te hogen: de versie van de scan (voor PATCH en
    // afronden) blijft gelijk tijdens het invullen van antwoorden.
    ScanState.Counts counts = state.counts(scan.id, scan.selectedDomainIds);
    String status = ScanState.statusFor(counts);
    int updated = ScanEntity.update("status = ?1, progress = ?2, updatedAt = ?3 where id = ?4 and status <> ?5",
        status, counts.progress(), now, scan.id, ScanEntity.COMPLETED);
    if (updated != 1) {
      // Kan door de lock niet meer voorkomen, maar nooit stil een afgeronde scan wijzigen.
      throw ApiException.conflict("Scan is afgerond",
          "Antwoorden van een afgeronde scan kunnen niet meer worden gewijzigd.");
    }

    return new ScanDtos.SavedAnswer(question.id, answer.optionId, answer.explanation,
        List.copyOf(answer.evidenceReferences), answer.apiVersion(), counts.progress(), status, scan.version);
  }
}
