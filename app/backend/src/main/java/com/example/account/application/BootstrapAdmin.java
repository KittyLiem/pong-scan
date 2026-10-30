package com.example.account.application;

import com.example.account.domain.MemberEntity;
import com.example.audit.AuditService;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Optionele bootstrap van één beheerder bij het opstarten. Bestaat het account nog niet, dan wordt het aangemaakt
 * met rol admin. Bestaat het al als gewoon lid, dan wordt het NIET gepromoveerd (geen eigendomsbewijs). Het
 * wachtwoord wordt nooit gelogd.
 */
@ApplicationScoped
public class BootstrapAdmin {
  private static final Logger LOG = Logger.getLogger(BootstrapAdmin.class);
  public static final int MIN_PASSWORD_LENGTH = 12;

  public enum Outcome { NOT_CONFIGURED, CREATED, ALREADY_ADMIN, TAKEN_BY_MEMBER }

  private final AuditService audit;
  private final Optional<String> identifier;
  private final Optional<String> password;

  public BootstrapAdmin(AuditService audit,
      @ConfigProperty(name = "account.bootstrap-admin.identifier") Optional<String> identifier,
      @ConfigProperty(name = "account.bootstrap-admin.password") Optional<String> password) {
    this.audit = audit;
    this.identifier = identifier;
    this.password = password;
  }

  void onStart(@Observes StartupEvent event) {
    bootstrap(identifier.orElse(null), password.orElse(null));
  }

  @Transactional
  public Outcome bootstrap(String rawIdentifier, String rawPassword) {
    String id = AccountService.normalizeIdentifier(rawIdentifier);
    if (id.isEmpty() || rawPassword == null || rawPassword.isBlank()) {
      if (!id.isEmpty() || (rawPassword != null && !rawPassword.isBlank())) {
        LOG.warn("Beheerder-bootstrap overgeslagen: zowel ACCOUNT_BOOTSTRAP_ADMIN_IDENTIFIER als "
            + "ACCOUNT_BOOTSTRAP_ADMIN_PASSWORD moeten zijn ingesteld.");
      }
      return Outcome.NOT_CONFIGURED;
    }
    if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException("ACCOUNT_BOOTSTRAP_ADMIN_PASSWORD moet minimaal " + MIN_PASSWORD_LENGTH
          + " tekens bevatten.");
    }
    Optional<MemberEntity> existing = MemberEntity.byIdentifier(id);
    if (existing.isPresent()) {
      if (MemberEntity.ROLE_ADMIN.equals(existing.get().role)) {
        return Outcome.ALREADY_ADMIN;
      }
      LOG.warnf("Beheerder-bootstrap: de identifier %s is al in gebruik door een account zonder beheerrol; "
          + "dit account wordt NIET gepromoveerd.", id);
      return Outcome.TAKEN_BY_MEMBER;
    }
    MemberEntity member = new MemberEntity();
    member.id = UUID.randomUUID();
    member.accountIdentifier = id;
    member.credentialHash = BcryptUtil.bcryptHash(rawPassword);
    member.role = MemberEntity.ROLE_ADMIN;
    member.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    member.persist();
    audit.success(null, "admin.bootstrap", "member", member.id, null);
    LOG.infof("Beheerder-bootstrap: beheerder %s aangemaakt.", id);
    return Outcome.CREATED;
  }
}
