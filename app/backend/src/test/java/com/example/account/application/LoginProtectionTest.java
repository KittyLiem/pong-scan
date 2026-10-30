package com.example.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginProtectionTest {
  @Test
  void windowExpiresWithoutSleepingAndSuccessResetsFailures() {
    MutableClock clock = new MutableClock();
    LoginProtection protection = new LoginProtection(2, Duration.ofMinutes(15), clock);
    assertThat(protection.verify("account", () -> false)).isFalse();
    assertThat(protection.verify("account", () -> true)).isTrue();
    assertThat(protection.verify("account", () -> false)).isFalse();
    assertThat(protection.verify("account", () -> false)).isFalse();
    assertThatThrownBy(() -> protection.verify("account", () -> true)).isInstanceOfSatisfying(
        ApiException.class, exception -> {
          assertThat(exception.status()).isEqualTo(429);
          assertThat(exception.retryAfter()).isEqualTo(900);
        });
    clock.now = clock.now.plusSeconds(900);
    assertThat(protection.verify("account", () -> true)).isTrue();
  }

  @Test
  void limitsConcurrentVerificationsAndReleasesSlotsAfterFailure() throws Exception {
    LoginProtection protection = new LoginProtection(5, Duration.ofMinutes(15), Clock.systemUTC());
    CountDownLatch started = new CountDownLatch(8);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<Boolean>>();
      try {
        for (int i = 0; i < 8; i++) {
          String identifier = "account-" + i;
          tasks.add(executor.submit(() -> protection.verify(identifier, () -> {
            started.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(exception);
            }
            return true;
          })));
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> protection.verify("extra", () -> true))
            .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.status()).isEqualTo(503));
      } finally {
        release.countDown();
      }
      for (Future<Boolean> task : tasks) assertThat(task.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(protection.verify("extra", () -> true)).isTrue();
      assertThatThrownBy(() -> protection.verify("exception", () -> { throw new IllegalStateException(); }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(protection.verify("exception", () -> true)).isTrue();
    }
  }

  @Test
  void fullTableStillLimitsEveryIdentifierAndEvictsTheEarliestExpiring() {
    MutableClock clock = new MutableClock();
    LoginProtection protection = new LoginProtection(1, Duration.ofMinutes(15), clock, 2);
    assertThat(protection.verify("vol-1", () -> false)).isFalse();
    clock.now = clock.now.plusSeconds(60);
    assertThat(protection.verify("vol-2", () -> false)).isFalse();
    clock.now = clock.now.plusSeconds(60);
    // Tabel vol: een nieuwe identifier wordt tóch geteld en begrensd (geen fail-open, geen 503 voor iedereen).
    assertThat(protection.verify("doelwit", () -> false)).isFalse();
    assertThatThrownBy(() -> protection.verify("doelwit", () -> true)).isInstanceOfSatisfying(
        ApiException.class, exception -> assertThat(exception.status()).isEqualTo(429));
    // Het item dat het eerst verloopt (vol-1) is verwijderd. Opnieuw toevoegen verwijdert daarna vol-2 (vroegst
    // verlopend); het doelwit blijft begrensd.
    assertThat(protection.verify("vol-1", () -> true)).isTrue();
    assertThatThrownBy(() -> protection.verify("doelwit", () -> true)).isInstanceOfSatisfying(
        ApiException.class, exception -> assertThat(exception.status()).isEqualTo(429));
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-20T12:00:00Z");

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
