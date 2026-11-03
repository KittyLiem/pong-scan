package com.example.organization.application;

import com.example.account.domain.MemberEntity;
import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Hashing;
import com.example.common.Texts;
import com.example.organization.api.OrganizationDtos;
import com.example.organization.domain.OrganizationEntity;
import com.example.organization.domain.OrganizationMemberEntity;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OrganizationService {
  private final AuditService audit;

  public OrganizationService(AuditService audit) {
    this.audit = audit;
  }

  @Transactional
  public OrganizationDtos.OrganizationCollection list(UUID memberId) {
    List<OrganizationEntity> entities = OrganizationEntity.list(
        "id in (" + OrganizationEntity.ACCESSIBLE_IDS + ") order by createdAt, id",
        Parameters.with("memberId", memberId));
    return new OrganizationDtos.OrganizationCollection(entities.stream().map(OrganizationService::toResponse).toList());
  }

  @Transactional
  public OrganizationDtos.Organization create(
      UUID memberId, String idempotencyKey, OrganizationDtos.OrganizationInput input) {
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String name = input.name().trim();
    String coc = Texts.blankToNull(input.chamberOfCommerceNumber());
    String industry = Texts.blankToNull(input.industry());
    String info = Texts.blankToNull(input.additionalInformation());
    String fingerprint = Hashing.fingerprint(name, coc, industry, info);

    var replay = OrganizationEntity.byMemberAndIdempotencyKey(memberId, keyHash);
    if (replay.isPresent()) {
      if (!Hashing.constantTimeEquals(replay.get().requestFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      return toResponse(replay.get());
    }
    if (MemberEntity.isQuickscan(memberId)) {
      MemberEntity.lock(memberId);
      if (OrganizationEntity.count("id in (" + OrganizationEntity.ACCESSIBLE_IDS + ")",
          Parameters.with("memberId", memberId)) > 0) {
        audit.failure(memberId, "quickscan.denied", "member", memberId, null);
        throw ApiException.forbidden("Een quickscan-account heeft één organisatie.");
      }
    }

    OrganizationEntity organization = new OrganizationEntity();
    organization.id = UUID.randomUUID();
    organization.memberId = memberId;
    organization.name = name;
    organization.chamberOfCommerceNumber = coc;
    organization.industry = industry;
    organization.additionalInformation = info;
    organization.status = "active";
    organization.idempotencyKeyHash = keyHash;
    organization.requestFingerprint = fingerprint;
    organization.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    organization.persistAndFlush();
    OrganizationMemberEntity owner = new OrganizationMemberEntity();
    owner.organizationId = organization.id;
    owner.memberId = memberId;
    owner.role = OrganizationMemberEntity.OWNER;
    owner.createdAt = organization.createdAt;
    owner.persistAndFlush();
    audit.success(memberId, "organization.create", "organization", organization.id, organization.id);
    return toResponse(organization);
  }

  public static OrganizationDtos.Organization toResponse(OrganizationEntity organization) {
    return new OrganizationDtos.Organization(organization.id, organization.name,
        organization.chamberOfCommerceNumber, organization.industry, organization.additionalInformation,
        organization.status, organization.version);
  }
}
