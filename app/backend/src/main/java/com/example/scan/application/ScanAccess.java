package com.example.scan.application;

import com.example.common.ApiException;
import com.example.organization.domain.OrganizationEntity;
import com.example.scan.domain.ScanEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.UUID;

/**
 * Toegangscontrole: een lid ziet alleen scans van organisaties waarvan het lid is (organization_members);
 * anders 404 (geen informatielek).
 */
@ApplicationScoped
public class ScanAccess {

  public ScanEntity load(UUID memberId, UUID scanId) {
    return ScanEntity.<ScanEntity>find(
            "id = :scanId and organizationId in (" + OrganizationEntity.ACCESSIBLE_IDS + ")",
            Parameters.with("scanId", scanId).and("memberId", memberId))
        .firstResultOptional()
        .orElseThrow(() -> ApiException.notFound("Scan niet gevonden."));
  }

  /**
   * Laadt de scan met een PESSIMISTIC_WRITE-lock op de scanrij (SELECT ... FOR UPDATE) en ververst de toestand.
   * Gebruikt door alle schrijvende scanacties zodat bijv. afronden en antwoorden opslaan elkaar uitsluiten; de
   * aanroeper controleert de status pas ná het verkrijgen van de lock.
   */
  public ScanEntity lock(UUID memberId, UUID scanId) {
    ScanEntity scan = load(memberId, scanId);
    Panache.getEntityManager().refresh(scan, LockModeType.PESSIMISTIC_WRITE);
    return scan;
  }

  public void requireOrganization(UUID memberId, UUID organizationId) {
    if (!OrganizationEntity.accessible(memberId, organizationId)) {
      throw ApiException.notFound("Organisatie niet gevonden.");
    }
  }
}
