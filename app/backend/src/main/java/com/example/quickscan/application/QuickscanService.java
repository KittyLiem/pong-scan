package com.example.quickscan.application;

import com.example.account.domain.MemberEntity;
import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.organization.domain.OrganizationEntity;
import com.example.scan.api.ScanDtos;
import com.example.scan.application.ScanService;
import com.example.scan.domain.ScanEntity;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

/** Opent de quickscan van een quickscan-account, of maakt hem aan zolang de limiet per account dat toelaat. */
@ApplicationScoped
public class QuickscanService {
  private final QuickscanSettings settings;
  private final ScanService scans;
  private final AuditService audit;

  public QuickscanService(QuickscanSettings settings, ScanService scans, AuditService audit) {
    this.settings = settings;
    this.scans = scans;
    this.audit = audit;
  }

  public record Opened(ScanDtos.Scan scan, boolean created) {}

  @Transactional
  public Opened open(UUID memberId) {
    settings.requireEnabled();
    // De lock op het lid serialiseert gelijktijdige verzoeken: dubbelklikken levert één scan op.
    MemberEntity member = MemberEntity.lock(memberId);
    if (member == null || !MemberEntity.PLAN_QUICKSCAN.equals(member.plan)) {
      audit.failure(memberId, "quickscan.denied", "member", memberId, null);
      throw ApiException.forbidden("Alleen een quickscan-account heeft een quickscan.");
    }

    List<ScanEntity> existing = ScanEntity.list("kind = :kind and organizationId in ("
        + OrganizationEntity.ACCESSIBLE_IDS + ") order by createdAt desc, id",
        Parameters.with("memberId", memberId).and("kind", ScanEntity.KIND_QUICK));
    var open = existing.stream().filter(scan -> !scan.isCompleted()).findFirst();
    if (open.isPresent()) {
      return new Opened(scans.toResponse(open.get()), false);
    }
    if (existing.size() >= settings.maxPerAccount()) {
      if (existing.isEmpty()) {
        throw ApiException.forbidden("Er is geen quickscan beschikbaar voor dit account.");
      }
      return new Opened(scans.toResponse(existing.getFirst()), false);
    }

    List<OrganizationEntity> organizations = OrganizationEntity.list(
        "id in (" + OrganizationEntity.ACCESSIBLE_IDS + ") order by createdAt, id",
        Parameters.with("memberId", memberId));
    if (organizations.isEmpty()) {
      throw ApiException.conflict("Organisatie ontbreekt",
          "Leg eerst de organisatie vast waarvoor u de quickscan doet.");
    }
    UUID organizationId = organizations.getFirst().id;
    ScanDtos.Scan scan = scans.createQuick(memberId, organizationId);
    audit.success(memberId, "quickscan.create", "scan", scan.id(), organizationId);
    return new Opened(scan, true);
  }
}
