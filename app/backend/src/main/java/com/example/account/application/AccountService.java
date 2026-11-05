package com.example.account.application;

import com.example.account.api.AccountDtos;
import com.example.account.domain.MemberEntity;
import com.example.account.domain.SessionEntity;
import com.example.audit.AuditService;
import com.example.common.ApiException;
import com.example.common.Hashing;
import com.example.common.Problem;
import com.example.common.Texts;
import com.example.quickscan.application.QuickscanSettings;
import com.example.security.TokenService;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AccountService {
  /** Hash van een willekeurig wachtwoord: houdt de responstijd gelijk bij onbekende accounts. */
  private static final String DUMMY_HASH = BcryptUtil.bcryptHash(UUID.randomUUID().toString());

  private final TokenService tokens;
  private final AuditService audit;
  private final LoginProtection loginProtection;
  private final LoginFailureStore loginFailures;
  private final QuickscanSettings quickscan;
  @ConfigProperty(name = "app.marketing.consent-version", defaultValue = "2026-11")
  String marketingConsentVersion;

  public AccountService(TokenService tokens, AuditService audit, LoginProtection loginProtection,
      LoginFailureStore loginFailures, QuickscanSettings quickscan) {
    this.tokens = tokens;
    this.audit = audit;
    this.loginProtection = loginProtection;
    this.loginFailures = loginFailures;
    this.quickscan = quickscan;
  }

  public static String normalizeIdentifier(String identifier) {
    return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public AccountDtos.Member register(String idempotencyKey, AccountDtos.RegistrationInput input) {
    String identifier = normalizeIdentifier(input.accountIdentifier());
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    if (input.credential().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new ApiException(400, "Ongeldige invoer", "Een of meer velden zijn ontbrekend of ongeldig.",
          List.of(new Problem.FieldError("credential",
              "Een wachtwoord mag maximaal 72 bytes zijn.")));
    }
    boolean quickscanEntry = AccountDtos.ENTRY_QUICKSCAN.equals(input.entry());
    boolean acceptedTerms = Boolean.TRUE.equals(input.acceptedTerms());
    boolean marketingConsent = Boolean.TRUE.equals(input.marketingConsent());
    // Zonder entry blijft de vingerafdruk gelijk aan die van bestaande registraties (replays blijven geldig).
    String fingerprint = quickscanEntry
        ? Hashing.fingerprint(identifier, input.entry(), acceptedTerms, marketingConsent)
        : Hashing.fingerprint(identifier);

    Optional<MemberEntity> replay = MemberEntity.byRegistrationKeyHash(keyHash);
    if (replay.isPresent()) {
      if (!Hashing.constantTimeEquals(replay.get().registrationFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      return toMember(replay.get());
    }
    if (quickscanEntry && !quickscan.enabled()) {
      throw new ApiException(422, "Quickscan niet beschikbaar", "Op deze omgeving kan geen quickscan worden gestart.",
          List.of(new Problem.FieldError("entry", "quickscan is niet beschikbaar")));
    }
    if (quickscanEntry && !acceptedTerms) {
      throw new ApiException(400, "Ongeldige invoer", "Een of meer velden zijn ontbrekend of ongeldig.",
          List.of(new Problem.FieldError("acceptedTerms", "Ga akkoord met de voorwaarden en het privacybeleid.")));
    }
    if (MemberEntity.byIdentifier(identifier).isPresent()) {
      throw ApiException.conflict("Account bestaat al", "Er is al een account met dit e-mailadres.");
    }

    MemberEntity member = new MemberEntity();
    member.id = UUID.randomUUID();
    member.accountIdentifier = identifier;
    member.credentialHash = BcryptUtil.bcryptHash(input.credential());
    // Registratie levert altijd een gewoon lid op; beheerders ontstaan alleen via de bootstrap of de database.
    member.role = MemberEntity.ROLE_MEMBER;
    member.plan = quickscanEntry ? MemberEntity.PLAN_QUICKSCAN : MemberEntity.PLAN_FULL;
    member.registrationKeyHash = keyHash;
    member.registrationFingerprint = fingerprint;
    member.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    if (quickscanEntry) {
      // Tijdstip van de server, niet van de browser; de versie maakt latere wijzigingen van de voorwaarden herleidbaar.
      member.termsAcceptedAt = member.createdAt;
      member.termsVersion = quickscan.termsVersion();
      // Optioneel: tijdstip en versie van de tekst alleen bij gegeven toestemming.
      member.marketingConsent = marketingConsent;
      if (marketingConsent) {
        member.marketingConsentAt = member.createdAt;
        member.marketingConsentVersion = marketingConsentVersion;
      }
    }
    member.persist();
    audit.success(member.id, "account.registered", "member", member.id, null);
    if (quickscanEntry) {
      audit.success(member.id, "account.terms.accepted", "member", member.id, null);
    }
    if (member.marketingConsent) {
      audit.success(member.id, "account.marketing.consent.granted", "member", member.id, null);
    }
    return toMember(member);
  }

  @Transactional
  public AccountDtos.Session createSession(String idempotencyKey, AccountDtos.SessionInput input) {
    String identifier = normalizeIdentifier(input.accountIdentifier());
    String keyHash = Hashing.requireIdempotencyKey(idempotencyKey);
    String desiredRoute = Texts.blankToNull(input.desiredRoute());
    String fingerprint = Hashing.fingerprint(identifier, desiredRoute);
    Instant now = Instant.now();

    Optional<SessionEntity> replay = SessionEntity.byIdempotencyKeyHash(keyHash);
    if (replay.isPresent()) {
      SessionEntity session = replay.get();
      if (!Hashing.constantTimeEquals(session.requestFingerprint, fingerprint)) {
        throw ApiException.idempotencyConflict();
      }
      if (session.revokedAt != null || !session.expiresAt.isAfter(now)) {
        throw ApiException.conflict("Sessie niet meer geldig",
            "Deze sessie is verlopen of beëindigd. Gebruik een nieuwe X-Idempotency-Key om opnieuw aan te melden.");
      }
      MemberEntity member = MemberEntity.findById(session.memberId);
      verifyCredential(identifier, input.credential(), Optional.of(member));
      return toSession(session, member);
    }

    Optional<MemberEntity> found = MemberEntity.byIdentifier(identifier);
    verifyCredential(identifier, input.credential(), found);
    MemberEntity member = found.get();

    SessionEntity session = new SessionEntity();
    session.id = UUID.randomUUID();
    session.memberId = member.id;
    session.idempotencyKeyHash = keyHash;
    session.requestFingerprint = fingerprint;
    session.desiredRoute = desiredRoute;
    session.issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
    session.expiresAt = session.issuedAt.plus(tokens.ttl());
    String token = tokens.issue(session);
    session.accessTokenHash = Hashing.sha256(token);
    session.persist();
    audit.success(member.id, "session.create", "session", session.id, null);
    return new AccountDtos.Session(token, "Bearer", session.expiresAt.atOffset(ZoneOffset.UTC),
        desiredRoute, toMember(member));
  }

  private void verifyCredential(String identifier, String credential, Optional<MemberEntity> found) {
    boolean valid;
    if (found.isPresent()) {
      // Bestaand account: persistente teller die een aanvaller niet kan wegdrukken.
      MemberEntity member = found.get();
      Instant now = Instant.now();
      long locked = loginFailures.reserveAttempt(member.id, now);
      if (locked > 0) throw LoginProtection.tooManyAttempts(locked);
      valid = loginProtection.verifyBounded(() -> credential.getBytes(StandardCharsets.UTF_8).length <= 72
          && BcryptUtil.matches(credential, member.credentialHash));
      if (valid) loginFailures.reset(member.id);
    } else {
      // Onbekend e-mailadres: zelfde kosten en hetzelfde 429-gedrag, met een begrensde teller in het geheugen.
      loginProtection.verify(identifier, () -> {
        BcryptUtil.matches(credential, DUMMY_HASH);
        return false;
      });
      valid = false;
    }
    if (!valid) {
      audit.failure(found.map(member -> member.id).orElse(null), "session.create", "session", null, null);
      throw ApiException.unauthorized("Het e-mailadres of wachtwoord is onjuist.");
    }
  }

  @Transactional
  public AccountDtos.Member me(UUID memberId) {
    MemberEntity member = MemberEntity.<MemberEntity>findByIdOptional(memberId)
        .orElseThrow(() -> ApiException.unauthorized("Aanmelden is vereist."));
    return toMember(member);
  }

  @Transactional
  public void revokeSession(UUID memberId, UUID sessionId) {
    SessionEntity session = SessionEntity.findById(sessionId);
    if (session != null && session.memberId.equals(memberId) && session.revokedAt == null) {
      session.revokedAt = Instant.now();
      audit.success(memberId, "session.revoke", "session", sessionId, null);
    }
  }

  private AccountDtos.Session toSession(SessionEntity session, MemberEntity member) {
    return new AccountDtos.Session(tokens.issue(session), "Bearer", session.expiresAt.atOffset(ZoneOffset.UTC),
        session.desiredRoute, toMember(member));
  }

  private AccountDtos.Member toMember(MemberEntity member) {
    return new AccountDtos.Member(member.id, member.accountIdentifier, member.role, member.plan,
        new AccountDtos.Features(quickscan.enabled(),
            quickscan.manualFollowupEnabled() && MemberEntity.ROLE_ADMIN.equals(member.role)),
        member.termsAcceptedAt, member.termsVersion,
        member.marketingConsent, member.marketingConsentAt);
  }
}
