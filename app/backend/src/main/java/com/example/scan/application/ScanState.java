package com.example.scan.application;

import com.example.scan.domain.AnswerEntity;
import com.example.scan.domain.ScanEntity;
import com.example.scan.domain.SnapshotQuestionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.UUID;

/**
 * Status- en voortgangsregels. Alleen vragen uit geselecteerde domeinen tellen mee.
 * <ul>
 *   <li>{@code concept}: geen selectie of nog geen antwoord;</li>
 *   <li>{@code in-progress}: minimaal één antwoord;</li>
 *   <li>{@code content-complete}: iedere geselecteerde vraag heeft een optie én een toelichting;</li>
 *   <li>{@code completed}: expliciet afgerond (onveranderlijk).</li>
 * </ul>
 * Voortgang = floor(beantwoorde geselecteerde vragen * 100 / geselecteerde vragen).
 */
@ApplicationScoped
public class ScanState {

  public record Counts(long total, long answered, long complete) {
    public int progress() {
      return total == 0 ? 0 : (int) Math.min(100, answered * 100 / total);
    }

    public boolean allComplete() {
      return total > 0 && complete == total;
    }
  }

  public Counts counts(UUID scanId, Collection<UUID> selectedDomainIds) {
    if (selectedDomainIds == null || selectedDomainIds.isEmpty()) {
      return new Counts(0, 0, 0);
    }
    long total = SnapshotQuestionEntity.count("scanId = ?1 and snapshotDomainId in ?2", scanId, selectedDomainIds);
    String selectedQuestions = "(select q.id from SnapshotQuestionEntity q where q.scanId = ?1 and q.snapshotDomainId in ?2)";
    long answered = AnswerEntity.count("scanId = ?1 and questionId in " + selectedQuestions, scanId, selectedDomainIds);
    long complete = AnswerEntity.count("scanId = ?1 and explanation is not null and questionId in "
        + selectedQuestions, scanId, selectedDomainIds);
    return new Counts(total, answered, complete);
  }

  public static String statusFor(Counts counts) {
    if (counts.total() == 0 || counts.answered() == 0) return ScanEntity.CONCEPT;
    if (counts.allComplete()) return ScanEntity.CONTENT_COMPLETE;
    return ScanEntity.IN_PROGRESS;
  }

  /** Werkt status en voortgang op de entiteit bij (niet voor afgeronde scans). */
  public Counts apply(ScanEntity scan) {
    Counts counts = counts(scan.id, scan.selectedDomainIds);
    if (!scan.isCompleted()) {
      scan.status = statusFor(counts);
      scan.progress = counts.progress();
    }
    return counts;
  }
}
