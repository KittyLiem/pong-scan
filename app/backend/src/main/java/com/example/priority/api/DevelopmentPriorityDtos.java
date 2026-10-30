package com.example.priority.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class DevelopmentPriorityDtos {
  private DevelopmentPriorityDtos() {}

  public record PriorityInput(
      @NotNull UUID developmentPointId,
      @NotNull @Pattern(regexp = "low|medium|high", message = "moet low, medium of high zijn") String impact,
      @NotNull @Pattern(regexp = "low|medium|high", message = "moet low, medium of high zijn") String urgency,
      @NotNull Boolean selected) {}

  public record PrioritiesInput(
      @NotNull @Size(min = 1, max = 50, message = "moet tussen 1 en 50 items bevatten") List<@Valid @NotNull PriorityInput> items) {}
}
