package com.example.admin.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {
  private AdminDtos() {}

  public record DomainWrite(
      @NotBlank @Size(max = 255) String name,
      @Size(max = 2000) String description,
      @NotNull @Min(0) @Max(10000) Integer order,
      @NotNull Boolean active,
      @NotNull @Min(0) Long version) {}

  public record Domain(
      UUID id, String name, String description, int order, boolean active, boolean required, long version, List<Question> questions) {}

  public record DomainCollection(List<Domain> items, String nextCursor) {}

  public record QuestionCreate(
      @NotBlank @Size(max = 255) String title,
      @NotBlank @Size(max = 2000) String text,
      @Size(max = 4000) String explanation,
      @NotNull @Min(0) @Max(10000) Integer order,
      @NotNull Boolean active,
      @NotNull @Min(0) Long version) {}

  public record QuestionUpdate(
      @NotNull UUID domainId,
      @NotBlank @Size(max = 255) String title,
      @NotBlank @Size(max = 2000) String text,
      @Size(max = 4000) String explanation,
      @NotNull @Min(0) @Max(10000) Integer order,
      @NotNull Boolean active,
      @NotNull @Min(0) Long version) {}

  public record Question(
      UUID id, UUID domainId, String title, String text, String explanation, int order, boolean active,
      long version, List<AnswerOption> options) {}

  public record AnswerOptionWrite(
      @NotNull @Min(value = 0, message = "moet tussen 0 en 5 liggen") @Max(value = 5, message = "moet tussen 0 en 5 liggen") Integer score,
      @NotBlank @Size(max = 255) String label,
      @Size(max = 2000) String description,
      @NotNull @Min(0) @Max(10000) Integer order,
      @NotNull Boolean available,
      @NotNull @Min(0) Long version) {}

  public record AnswerOption(
      UUID id, UUID questionId, int score, String label, String description, int order, boolean available,
      long version) {}
}
