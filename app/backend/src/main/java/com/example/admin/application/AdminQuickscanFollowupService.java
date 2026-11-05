package com.example.admin.application;

import com.example.account.domain.MemberEntity;
import com.example.admin.api.AdminQuickscanDtos;
import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Cursors;
import com.example.organization.domain.OrganizationEntity;
import com.example.organization.domain.OrganizationMemberEntity;
import com.example.outreach.domain.MemberOutreachEntity;
import com.example.quickscan.application.QuickscanSettings;
import com.example.scan.domain.ScanEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Laat beheerders quickscan-accounts met marketingtoestemming eenmalig handmatig per e-mail benaderen, zolang er
 * geen automatische e-mailprocedure is. Het contact wordt vastgelegd in {@link MemberOutreachEntity}, zodat ook de
 * latere automatische procedure niemand een tweede keer mailt.
 */
@ApplicationScoped
public class AdminQuickscanFollowupService {
  public static final String STATUS_PENDING = "pending";
  public static final String STATUS_CONTACTED = "contacted";

  private static final String CAMPAIGN = MemberOutreachEntity.CAMPAIGN_QUICKSCAN_FOLLOWUP;
  private static final String HAS_QUICKSCAN = "exists (select 1 from ScanEntity s where s.kind = :kind"
      + " and s.organizationId in (select om.organizationId from OrganizationMemberEntity om"
      + " where om.memberId = m.id))";
  private static final String CONSENTED = "m.marketingConsent = true and m.marketingConsentWithdrawnAt is null";
  private static final String NOT_CONTACTED = "not exists (select 1 from MemberOutreachEntity o"
      + " where o.memberId = m.id and o.campaign = :campaign)";

  private final QuickscanSettings settings;
  private final AuditService audit;

  public AdminQuickscanFollowupService(QuickscanSettings settings, AuditService audit) {
    this.settings = settings;
    this.audit = audit;
  }

  /**
   * {@code pending}: met toestemming en nog niet benaderd (oudste registratie eerst).
   * {@code contacted}: al benaderd binnen de campagne, handmatig of automatisch (laatst benaderd eerst).
   */
  @Transactional
  public AdminQuickscanDtos.AccountCollection list(String status, String cursor, Integer pageSize) {
    settings.requireManualFollowupEnabled();
    int size = Cursors.pageSize(pageSize);
    int start = Cursors.decode(cursor);
    String filter = status == null || status.isBlank() ? STATUS_PENDING : status;

    List<MemberEntity> members;
    if (STATUS_PENDING.equals(filter)) {
      members = MemberEntity.<MemberEntity>find("select m from MemberEntity m where m.plan = :plan and "
              + CONSENTED + " and " + HAS_QUICKSCAN + " and " + NOT_CONTACTED + " order by m.createdAt, m.id",
          Parameters.with("plan", MemberEntity.PLAN_QUICKSCAN).and("kind", ScanEntity.KIND_QUICK)
              .and("campaign", CAMPAIGN))
          .range(start, start + size).list();
    } else if (STATUS_CONTACTED.equals(filter)) {
      members = MemberEntity.<MemberEntity>find("select m from MemberEntity m, MemberOutreachEntity o"
              + " where o.memberId = m.id and o.campaign = :campaign and m.plan = :plan"
              + " order by o.createdAt desc, m.id",
          Parameters.with("plan", MemberEntity.PLAN_QUICKSCAN).and("campaign", CAMPAIGN))
          .range(start, start + size).list();
    } else {
      throw ApiException.badRequest("status moet 'pending' of 'contacted' zijn.");
    }

    boolean hasNext = members.size() > size;
    List<MemberEntity> page = members.stream().limit(size).toList();
    return new AdminQuickscanDtos.AccountCollection(toDtos(page), hasNext ? Cursors.encode(start + size) : null,
        countDeclined());
  }

  /** Quickscan-accounts met een quickscan zonder (geldige) marketingtoestemming. */
  private static long countDeclined() {
    return MemberEntity.count("from MemberEntity m where m.plan = :plan and not (" + CONSENTED + ") and "
        + HAS_QUICKSCAN, Parameters.with("plan", MemberEntity.PLAN_QUICKSCAN).and("kind", ScanEntity.KIND_QUICK));
  }

  /** Legt vast dat de beheerder dit account handmatig heeft gemaild. Kan per account maar één keer (anders 409). */
  @Transactional
  public AdminQuickscanDtos.Account markContacted(UUID actor, UUID memberId) {
    settings.requireManualFollowupEnabled();
    // De lock op het lid serialiseert gelijktijdige markeringen (ook door een latere automatische procedure).
    MemberEntity member = MemberEntity.lock(memberId);
    if (member == null || !MemberEntity.PLAN_QUICKSCAN.equals(member.plan) || !hasQuickscan(memberId)) {
      throw ApiException.notFound("Er is geen quickscan-account met een quickscan gevonden.");
    }
    if (MemberOutreachEntity.byMemberAndCampaign(memberId, CAMPAIGN).isPresent()) {
      throw ApiException.conflict("Al benaderd", "Dit account is al benaderd voor de opvolging van de quickscan.");
    }
    if (!member.marketingConsent || member.marketingConsentWithdrawnAt != null) {
      audit.failure(actor, "admin.quickscan.followup.contacted", "member", memberId, null);
      throw ApiException.unprocessable("Geen toestemming",
          "Dit account heeft geen toestemming (meer) gegeven voor marketing per e-mail.");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    MemberOutreachEntity outreach = new MemberOutreachEntity();
    outreach.id = UUID.randomUUID();
    outreach.memberId = memberId;
    outreach.campaign = CAMPAIGN;
    outreach.channel = MemberOutreachEntity.CHANNEL_MANUAL;
    outreach.status = MemberOutreachEntity.STATUS_SENT;
    outreach.consentVersion = member.marketingConsentVersion;
    outreach.createdBy = actor;
    outreach.createdAt = now;
    outreach.sentAt = now;
    outreach.persist();
    Panache.getEntityManager().flush();

    AdminQuickscanDtos.Account account = toDtos(List.of(member)).getFirst();
    audit.success(actor, "admin.quickscan.followup.contacted", "member", memberId, organizationIdOf(account));
    return account;
  }

  private static boolean hasQuickscan(UUID memberId) {
    return ScanEntity.count("kind = :kind and organizationId in (" + OrganizationEntity.ACCESSIBLE_IDS + ")",
        Parameters.with("kind", ScanEntity.KIND_QUICK).and("memberId", memberId)) > 0;
  }

  private static UUID organizationIdOf(AdminQuickscanDtos.Account account) {
    if (account.quickscan() == null) return null;
    ScanEntity scan = ScanEntity.findById(account.quickscan().scanId());
    return scan == null ? null : scan.organizationId;
  }

  private static List<AdminQuickscanDtos.Account> toDtos(List<MemberEntity> members) {
    if (members.isEmpty()) return List.of();
    List<UUID> memberIds = members.stream().map(member -> member.id).toList();

    Map<UUID, Set<UUID>> organizationsByMember = new HashMap<>();
    OrganizationMemberEntity.<OrganizationMemberEntity>list("memberId in ?1", memberIds)
        .forEach(link -> organizationsByMember.computeIfAbsent(link.memberId, key -> new HashSet<>())
            .add(link.organizationId));
    Set<UUID> organizationIds = organizationsByMember.values().stream()
        .flatMap(Set::stream).collect(Collectors.toSet());

    List<ScanEntity> scans = organizationIds.isEmpty() ? List.of()
        : ScanEntity.list("kind = ?1 and organizationId in ?2 order by createdAt desc, id",
            ScanEntity.KIND_QUICK, organizationIds);
    Map<UUID, String> organizationNames = organizationIds.isEmpty() ? Map.of()
        : OrganizationEntity.<OrganizationEntity>list("id in ?1", organizationIds).stream()
            .collect(Collectors.toMap(organization -> organization.id, organization -> organization.name));

    Map<UUID, MemberOutreachEntity> contacts = MemberOutreachEntity.byMembersAndCampaign(memberIds, CAMPAIGN)
        .stream().collect(Collectors.toMap(outreach -> outreach.memberId, Function.identity()));
    Set<UUID> creatorIds = contacts.values().stream().map(outreach -> outreach.createdBy)
        .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    Map<UUID, String> creators = creatorIds.isEmpty() ? Map.of()
        : MemberEntity.<MemberEntity>list("id in ?1", creatorIds).stream()
            .collect(Collectors.toMap(creator -> creator.id, creator -> creator.accountIdentifier));

    return members.stream().map(member -> {
      Set<UUID> own = organizationsByMember.getOrDefault(member.id, Set.of());
      ScanEntity latest = scans.stream().filter(scan -> own.contains(scan.organizationId)).findFirst().orElse(null);
      AdminQuickscanDtos.Quickscan quickscan = latest == null ? null : new AdminQuickscanDtos.Quickscan(
          latest.id, latest.status, latest.progress, latest.createdAt, latest.completedAt);
      String organizationName = latest == null ? null : organizationNames.get(latest.organizationId);
      MemberOutreachEntity outreach = contacts.get(member.id);
      AdminQuickscanDtos.Contact contact = outreach == null ? null : new AdminQuickscanDtos.Contact(
          outreach.channel, outreach.status, outreach.createdAt,
          outreach.createdBy == null ? null : creators.get(outreach.createdBy));
      return new AdminQuickscanDtos.Account(member.id, member.accountIdentifier, member.createdAt,
          organizationName, quickscan, member.marketingConsent, member.marketingConsentAt,
          member.marketingConsentVersion, member.marketingConsentWithdrawnAt, contact);
    }).toList();
  }
}
