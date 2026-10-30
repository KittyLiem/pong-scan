package com.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.account.api.AccountDtos;
import com.example.account.application.AccountService;
import com.example.account.domain.MemberEntity;
import com.example.account.domain.SessionEntity;
import com.example.common.ApiException;
import com.example.common.Hashing;
import com.example.organization.api.OrganizationDtos;
import com.example.organization.application.OrganizationService;
import com.example.scan.api.ScanDtos;
import com.example.scan.application.ScanService;
import com.example.scan.domain.ScanEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Service-integratie inclusief transacties en database, zonder HTTP-transport. */
@QuarkusTest
class SecurityServiceTest {
  @Inject AccountService accounts;
  @Inject OrganizationService organizations;
  @Inject ScanService scans;

  @Test
  void passwordFreeFingerprintsAndReplaysUseBcrypt() {
    String email = Api.uniqueEmail("service-security");
    String password = "é".repeat(36);
    String registrationKey = Api.key();
    var member = accounts.register(registrationKey, new AccountDtos.RegistrationInput(email, password));
    assertThat(accounts.register(registrationKey, new AccountDtos.RegistrationInput(email, "ander-wachtwoord")))
        .isEqualTo(member);
    assertThatThrownBy(() -> accounts.register(registrationKey,
        new AccountDtos.RegistrationInput(Api.uniqueEmail("anders"), password)))
        .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.status()).isEqualTo(409));
    String key = Api.key();
    var input = new AccountDtos.SessionInput(email, password, "/scans");
    var session = accounts.createSession(key, input);
    assertThatThrownBy(() -> accounts.createSession(key,
        new AccountDtos.SessionInput(email, password + "A", "/scans")))
        .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.status()).isEqualTo(401));
    assertThat(accounts.createSession(key, input)).isEqualTo(session);
    QuarkusTransaction.requiringNew().run(() -> {
      var storedMember = MemberEntity.byIdentifier(email).orElseThrow();
      var storedSession = SessionEntity.byIdempotencyKeyHash(Hashing.sha256(key)).orElseThrow();
      assertThat(storedMember.registrationFingerprint).isNotEqualTo(Hashing.fingerprint(email, Hashing.sha256(password)));
      assertThat(storedSession.requestFingerprint).isNotEqualTo(Hashing.fingerprint(
          email, Hashing.sha256(password), storedSession.desiredRoute));
    });
    assertThatThrownBy(() -> accounts.register(Api.key(),
        new AccountDtos.RegistrationInput(Api.uniqueEmail("te-lang"), password + "A")))
        .isInstanceOfSatisfying(ApiException.class, exception -> {
          assertThat(exception.status()).isEqualTo(400);
          assertThat(exception.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("credential");
            assertThat(error.message()).isEqualTo("Een wachtwoord mag maximaal 72 bytes zijn.");
          });
        });
  }

  @Test
  void simultaneousCreationCannotExceedDailyQuota() throws Exception {
    var member = accounts.register(Api.key(), new AccountDtos.RegistrationInput(
        Api.uniqueEmail("quota-race"), "geheim-wachtwoord"));
    var organization = organizations.create(member.id(), Api.key(),
        new OrganizationDtos.OrganizationInput("Quotumrace", null, null, null));
    var input = new ScanDtos.ScanCreateInput("Scan", "2026", List.of());
    QuarkusTransaction.requiringNew().run(() -> {
      for (int i = 0; i < 19; i++) {
        ScanEntity scan = new ScanEntity();
        scan.id = java.util.UUID.randomUUID();
        scan.organizationId = organization.id();
        scan.name = "Bestaande scan";
        scan.assessmentPeriod = "2026";
        scan.status = ScanEntity.CONCEPT;
        scan.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        scan.updatedAt = scan.createdAt;
        scan.persist();
      }
    });
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Integer> create = () -> {
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");
        try {
          scans.create(member.id(), organization.id(), Api.key(), input);
          return 201;
        } catch (ApiException exception) {
          assertThat(exception.title()).isEqualTo("Scanlimiet bereikt");
          return exception.status();
        }
      };
      var first = executor.submit(create);
      var second = executor.submit(create);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 429);
    }
    assertThat(QuarkusTransaction.requiringNew().call(() ->
        ScanEntity.count("organizationId", organization.id()))).isEqualTo(20);
  }
}
