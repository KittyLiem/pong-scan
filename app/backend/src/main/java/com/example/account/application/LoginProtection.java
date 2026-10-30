package com.example.account.application;

import com.example.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Proceslokale bescherming voor e-mailadressen zonder account (zodat die zich gedragen als bestaande accounts) en een
 * begrenzing van gelijktijdige bcrypt-verificaties. Bestaande accounts gebruiken de persistente
 * {@link LoginFailureStore}; nginx begrenst daarnaast het verkeer per client-IP.
 */
@ApplicationScoped
public class LoginProtection {
  private final Map<String, Attempts> attempts = new HashMap<>();
  private final Semaphore verifications = new Semaphore(8);
  private final int maxFailures;
  private final Duration window;
  private final Clock clock;
  private final int maxTracked;

  private static class Attempts {
    final Instant expiresAt;
    int failures;
    int pending;

    Attempts(Instant expiresAt) {
      this.expiresAt = expiresAt;
    }
  }

  @Inject
  public LoginProtection(@ConfigProperty(name = "account.login.max-failures", defaultValue = "5") int maxFailures,
      @ConfigProperty(name = "account.login.window", defaultValue = "PT15M") Duration window) {
    this(maxFailures, window, Clock.systemUTC(), 10000);
  }

  LoginProtection(int maxFailures, Duration window, Clock clock) {
    this(maxFailures, window, clock, 10000);
  }

  LoginProtection(int maxFailures, Duration window, Clock clock, int maxTracked) {
    if (maxFailures < 1 || window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("De aanmeldlimiet en het tijdvenster moeten positief zijn.");
    }
    this.maxFailures = maxFailures;
    this.window = window;
    this.clock = clock;
    this.maxTracked = maxTracked;
  }

  public boolean verify(String identifier, BooleanSupplier verification) {
    Attempts state;
    synchronized (attempts) {
      Instant now = clock.instant();
      attempts.entrySet().removeIf(entry -> entry.getValue().pending == 0
          && !entry.getValue().expiresAt.isAfter(now));
      state = attempts.get(identifier);
      // Iedere identifier (ook onbekend) wordt altijd geteld, zodat de limiet per account nooit wegvalt. Is de tabel
      // vol, dan verdwijnt het item dat het eerst verloopt; een teller resetten kost dan ~maxTracked verzoeken.
      if (state == null) {
        if (attempts.size() >= maxTracked && !evictEarliestExpiring()) throw unavailable();
        state = new Attempts(now.plus(window));
        attempts.put(identifier, state);
      }
      if (state.failures + state.pending >= maxFailures) {
        long seconds = Math.max(1, (Duration.between(now, state.expiresAt).toMillis() + 999) / 1000);
        throw tooManyAttempts(seconds);
      }
      state.pending++;
    }
    Attempts tracked = state;
    boolean acquired = false;
    boolean valid = false;
    boolean completed = false;
    try {
      acquired = verifications.tryAcquire(100, TimeUnit.MILLISECONDS);
      if (!acquired) throw unavailable();
      valid = verification.getAsBoolean();
      completed = true;
      return valid;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } finally {
      if (acquired) verifications.release();
      if (tracked != null) {
        synchronized (attempts) {
          tracked.pending--;
          if (completed) {
            if (valid) tracked.failures = 0;
            else tracked.failures++;
          }
          if (tracked.pending == 0 && tracked.failures == 0) attempts.remove(identifier, tracked);
        }
      }
    }
  }

  /** Verwijdert het niet-actieve item dat het eerst verloopt; false als alle items in behandeling zijn. */
  private boolean evictEarliestExpiring() {
    String victim = null;
    Instant earliest = null;
    for (var entry : attempts.entrySet()) {
      if (entry.getValue().pending > 0) continue;
      if (earliest == null || entry.getValue().expiresAt.isBefore(earliest)) {
        earliest = entry.getValue().expiresAt;
        victim = entry.getKey();
      }
    }
    if (victim == null) return false;
    attempts.remove(victim);
    return true;
  }

  /** Alleen de begrenzing van gelijktijdige verificaties, zonder teller (voor accounts met een persistente teller). */
  public boolean verifyBounded(BooleanSupplier verification) {
    boolean acquired = false;
    try {
      acquired = verifications.tryAcquire(100, TimeUnit.MILLISECONDS);
      if (!acquired) throw unavailable();
      return verification.getAsBoolean();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } finally {
      if (acquired) verifications.release();
    }
  }

  static ApiException tooManyAttempts(long seconds) {
    return new ApiException(429, "Te veel aanmeldpogingen",
        "Er zijn te veel mislukte aanmeldpogingen voor dit e-mailadres. Probeer het later opnieuw.")
        .retryAfter(seconds);
  }

  private static ApiException unavailable() {
    return new ApiException(503, "Aanmelden tijdelijk niet beschikbaar",
        "Er worden te veel aanmeldingen tegelijk verwerkt. Probeer het later opnieuw.").retryAfter(1);
  }
}
