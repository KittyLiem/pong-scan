package com.example.outreach.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Eén contactmoment met een account binnen een campagne. Per (account, campagne) bestaat hooguit één rij, zodat
 * handmatige en (later) automatische verzending elkaar nooit dubbel laten mailen.
 */
@Entity
@Table(name = "member_outreach")
public class MemberOutreachEntity extends PanacheEntityBase {
  /** Opvolging van een quickscan: één keer contact met een account dat een quickscan deed. */
  public static final String CAMPAIGN_QUICKSCAN_FOLLOWUP = "quickscan-followup";
  public static final String CHANNEL_MANUAL = "manual";
  public static final String CHANNEL_AUTOMATIC = "automatic";
  public static final String STATUS_QUEUED = "queued";
  public static final String STATUS_SENT = "sent";
  public static final String STATUS_FAILED = "failed";
  public static final String STATUS_BOUNCED = "bounced";

  @Id public UUID id;
  @Column(name = "member_id", nullable = false) public UUID memberId;
  @Column(nullable = false) public String campaign;
  @Column(nullable = false) public String channel;
  @Column(nullable = false) public String status;
  @Column(name = "consent_version") public String consentVersion;
  @Column(name = "provider_message_id") public String providerMessageId;
  @Column(name = "created_by") public UUID createdBy;
  @Column(name = "created_at", nullable = false) public OffsetDateTime createdAt;
  @Column(name = "sent_at") public OffsetDateTime sentAt;

  public static Optional<MemberOutreachEntity> byMemberAndCampaign(UUID memberId, String campaign) {
    return find("memberId = ?1 and campaign = ?2", memberId, campaign).firstResultOptional();
  }

  public static List<MemberOutreachEntity> byMembersAndCampaign(Collection<UUID> memberIds, String campaign) {
    if (memberIds.isEmpty()) return List.of();
    return list("memberId in ?1 and campaign = ?2", memberIds, campaign);
  }
}
