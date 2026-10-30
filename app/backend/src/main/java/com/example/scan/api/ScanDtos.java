package com.example.scan.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ScanDtos {
  private ScanDtos() {}

  public static final String PERIOD_PATTERN = "^(\\d{4}|Q[1-4] \\d{4}|H[12] \\d{4})$";
  public static final String PERIOD_MESSAGE = "moet een jaar (2026), kwartaal (Q1 2026) of halfjaar (H1 2026) zijn";

  public record ScanCreateInput(
      @NotBlank @Size(max = 120, message = "moet tussen 1 en 120 tekens bevatten") String name,
      @NotBlank @Pattern(regexp = PERIOD_PATTERN, message = PERIOD_MESSAGE) String assessmentPeriod,
      @Size(max = 100) List<@NotNull UUID> initialSelectedDomainIds) {}

  public record OrganizationGoal(
      @NotBlank @Size(max = 255) String name,
      @NotNull @Pattern(regexp = "high|medium|low", message = "moet high, medium of low zijn") String priority,
      @Size(max = 2000) String customText) {}

  public record ScanUpdateInput(
      @NotNull @Min(0) Long version,
      @Size(min = 1, max = 120, message = "moet tussen 1 en 120 tekens bevatten") String name,
      @Pattern(regexp = PERIOD_PATTERN, message = PERIOD_MESSAGE) String assessmentPeriod,
      @Size(min = 1, max = 100, message = "selecteer minimaal één kennisgebied") List<@NotNull UUID> selectedDomainIds,
      @Size(max = 10, message = "mag maximaal 10 doelen bevatten") List<@Valid @NotNull OrganizationGoal> goals) {}

  public record CompletionInput(
      @NotNull @Min(0) Long version,
      @Size(max = 4000) String generalComments) {}

  public record RevisionInput(
      @NotNull @Pattern(regexp = "copy|new-revision", message = "moet copy of new-revision zijn") String mode) {}

  public record Scan(
      UUID id,
      UUID organizationId,
      String name,
      String assessmentPeriod,
      String status,
      int progress,
      int revision,
      UUID revisionOf,
      List<UUID> selectedDomainIds,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      OffsetDateTime completedAt,
      String generalComments,
      List<OrganizationGoal> goals) {}

  public record ScanCollection(List<Scan> items, String nextCursor) {}

  // ---- Snapshot-inhoud van een scan ----

  public record ContentOption(UUID id, String label, String description, int score, int order) {}

  public record ContentAnswer(UUID optionId, String explanation, List<String> evidenceReferences, long version) {}

  public record ContentQuestion(
      UUID id, String title, String text, String explanation, int order, List<ContentOption> options,
      ContentAnswer answer) {}

  public record ContentDomain(
      UUID id, String name, String description, int order, boolean selected, int questionCount,
      int answeredCount, List<ContentQuestion> questions) {}

  public record ScanContent(UUID scanId, String status, boolean editable, List<ContentDomain> domains) {}

  // ---- Antwoorden ----

  public record AnswerInput(
      @NotNull UUID optionId,
      @Size(max = 4000) String explanation,
      @Size(max = 10, message = "mag maximaal 10 verwijzingen bevatten")
      List<@NotBlank @Size(max = 2048) String> evidenceReferences,
      @NotNull @Min(0) @Max(Long.MAX_VALUE) Long version) {}

  public record SavedAnswer(
      UUID questionId,
      UUID optionId,
      String explanation,
      List<String> evidenceReferences,
      long version,
      int progress,
      String scanStatus,
      long scanVersion) {}
}
