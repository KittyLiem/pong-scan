package com.example.audit;

import com.example.common.RequestCorrelation;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/** Schrijft audit-events. Succesvolle acties lopen mee in de transactie; mislukte in een eigen transactie. */
@ApplicationScoped
public class AuditService {
  private static final Logger LOG = Logger.getLogger("audit");

  public static final String SUCCESS = "success";
  public static final String FAILURE = "failure";

  @Transactional(Transactional.TxType.REQUIRED)
  public void success(UUID actor, String action, String resourceType, UUID resourceId, UUID organizationId) {
    write(actor, action, resourceType, resourceId, organizationId, SUCCESS);
  }

  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void failure(UUID actor, String action, String resourceType, UUID resourceId, UUID organizationId) {
    write(actor, action, resourceType, resourceId, organizationId, FAILURE);
  }

  private void write(UUID actor, String action, String resourceType, UUID resourceId, UUID organizationId,
      String outcome) {
    AuditEventEntity event = new AuditEventEntity();
    event.id = UUID.randomUUID();
    event.occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    event.actorMemberId = actor;
    event.action = action;
    event.resourceType = resourceType;
    event.resourceId = resourceId;
    event.organizationId = organizationId;
    event.correlationId = correlationId();
    event.outcome = outcome;
    event.persist();
    LOG.infof("audit action=%s outcome=%s resourceType=%s resourceId=%s organizationId=%s actor=%s",
        action, outcome, resourceType, resourceId, organizationId, actor);
  }

  private static String correlationId() {
    try {
      if (Arc.container().requestContext().isActive()) {
        String id = Arc.container().instance(RequestCorrelation.class).get().id();
        if (id != null) return id;
      }
    } catch (RuntimeException ignored) {
      // val terug op MDC
    }
    Object mdc = MDC.get("correlationId");
    return mdc == null ? null : mdc.toString();
  }
}
