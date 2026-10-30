package com.example.organization.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class OrganizationDtos {
  private OrganizationDtos() {}

  public record OrganizationInput(
      @NotBlank @Size(max = 255) String name,
      @Pattern(regexp = "^(\\d{8})?$", message = "moet uit precies 8 cijfers bestaan") String chamberOfCommerceNumber,
      @Size(max = 255) String industry,
      @Size(max = 4000) String additionalInformation) {}

  public record Organization(
      UUID id, String name, String chamberOfCommerceNumber, String industry, String additionalInformation,
      String status, long version) {}

  public record OrganizationCollection(List<Organization> items) {}
}
