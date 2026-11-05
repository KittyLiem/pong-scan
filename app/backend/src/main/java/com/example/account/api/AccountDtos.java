package com.example.account.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AccountDtos {
  private AccountDtos() {}

  public record RegistrationInput(
      @NotBlank @Email(message = "moet een geldig e-mailadres zijn") @Size(max = 320) String accountIdentifier,
      @NotBlank @Size(min = 8, message = "moet minimaal 8 tekens bevatten")
      @Size(max = 128, message = "mag maximaal 128 tekens bevatten") String credential,
      @Pattern(regexp = ENTRY_QUICKSCAN, message = "moet quickscan zijn of ontbreken") String entry,
      /** Verplicht {@code true} bij {@code entry = quickscan}; wordt met tijdstip en versie bij het account opgeslagen. */
      Boolean acceptedTerms) {

    public RegistrationInput(String accountIdentifier, String credential) {
      this(accountIdentifier, credential, null, null);
    }
  }

  /** Registratie via /quickscan levert een quickscan-account op. */
  public static final String ENTRY_QUICKSCAN = "quickscan";

  public record SessionInput(
      @NotBlank @Size(max = 320) String accountIdentifier,
      @NotBlank @Size(max = 128, message = "mag maximaal 128 tekens bevatten") String credential,
      @Size(max = 2048, message = "mag maximaal 2048 tekens bevatten")
      @Pattern(regexp = "^/(?![/\\\\]).*$", message = "moet een relatieve route zijn die met één '/' begint")
      String desiredRoute) {}

  public record Member(UUID id, String accountIdentifier, String role, String plan, Features features,
      OffsetDateTime termsAcceptedAt, String termsVersion) {}

  /** Functies die op deze installatie beschikbaar zijn. */
  public record Features(boolean quickscan) {}

  public record Session(
      String accessToken, String tokenType, OffsetDateTime expiresAt, String desiredRoute, Member member) {}
}
