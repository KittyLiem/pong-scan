package com.example.admin.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Handmatige opvolging van quickscan-accounts door beheerders. */
public final class AdminQuickscanDtos {
  private AdminQuickscanDtos() {}

  /** Een quickscan-account met de meest recente quickscan en het eventuele contactmoment. */
  public record Account(
      UUID memberId,
      String accountIdentifier,
      OffsetDateTime registeredAt,
      String organizationName,
      Quickscan quickscan,
      boolean marketingConsent,
      OffsetDateTime marketingConsentAt,
      String marketingConsentVersion,
      OffsetDateTime marketingConsentWithdrawnAt,
      Contact contact) {}

  public record Quickscan(
      UUID scanId, String status, int progress, OffsetDateTime createdAt, OffsetDateTime completedAt) {}

  /** Contactmoment binnen de campagne; {@code contactedBy} is leeg bij automatische verzending. */
  public record Contact(String channel, String status, OffsetDateTime contactedAt, String contactedBy) {}

  /**
   * {@code declinedCount}: quickscan-accounts met een quickscan die niet benaderd willen worden (geen
   * marketingtoestemming gegeven of de toestemming ingetrokken). Staat los van het filter en de paginering.
   */
  public record AccountCollection(List<Account> items, String nextCursor, long declinedCount) {}
}
