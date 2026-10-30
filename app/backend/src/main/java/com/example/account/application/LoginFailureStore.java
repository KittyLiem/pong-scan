package com.example.account.application;

import com.example.account.domain.MemberEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Persistente teller van mislukte aanmeldpogingen voor bestaande accounts. Staat in de database (niet in het geheugen),
 * zodat een aanvaller de teller van een doelwit niet kan wegdrukken door veel andere e-mailadressen te proberen.
 */
@ApplicationScoped
public class LoginFailureStore {
  @ConfigProperty(name = "account.login.max-failures", defaultValue = "5")
  int maxFailures;

  @ConfigProperty(name = "account.login.window", defaultValue = "PT15M")
  Duration window;

  /**
   * Reserveert atomair een aanmeldpoging vóór de wachtwoordcontrole: de poging telt direct als mislukt en wordt bij
   * succes teruggezet. Zo kunnen gelijktijdige pogingen de limiet niet omzeilen. Geeft de resterende blokkadeduur in
   * seconden terug, of 0 als de poging is toegestaan.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public long reserveAttempt(UUID memberId, Instant now) {
    MemberEntity member = MemberEntity.findById(memberId, LockModeType.PESSIMISTIC_WRITE);
    if (member == null) return 0;
    boolean windowActive = member.failedLoginWindowEnds != null && member.failedLoginWindowEnds.toInstant().isAfter(now);
    if (windowActive && member.failedLoginCount >= maxFailures) {
      Instant ends = member.failedLoginWindowEnds.toInstant();
      return Math.max(1, (Duration.between(now, ends).toMillis() + 999) / 1000);
    }
    // Directe update (zonder @Version-ophoging), zodat lopende transacties op dit account niet in een versieconflict lopen.
    if (windowActive) {
      MemberEntity.update("failedLoginCount = failedLoginCount + 1 where id = ?1", memberId);
    } else {
      MemberEntity.update("failedLoginCount = 1, failedLoginWindowEnds = ?1 where id = ?2",
          OffsetDateTime.ofInstant(now.plus(window), ZoneOffset.UTC), memberId);
    }
    return 0;
  }

  /** Zet de teller terug na een geslaagde aanmelding, in een eigen transactie en zonder versieafhankelijkheid. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void reset(UUID memberId) {
    MemberEntity.update("failedLoginCount = 0, failedLoginWindowEnds = null where id = ?1", memberId);
  }
}
