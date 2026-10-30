package com.example.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.ArcUndeclaredThrowableException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import jakarta.ws.rs.core.Response;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.Test;

class ProblemMappersTest {
  private final ProblemMappers mappers = new ProblemMappers();

  private static void assertVersionConflict(Response response) {
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
    Problem problem = (Problem) response.getEntity();
    assertThat(problem.title()).isEqualTo("Versieconflict");
    assertThat(problem.detail()).contains("intussen gewijzigd");
  }

  @Test
  void optimisticLockFailuresBecomeVersionConflict() {
    assertVersionConflict(mappers.mapOptimisticLock(new OptimisticLockException("stale"), null));
    assertVersionConflict(mappers.mapStaleState(new StaleObjectStateException("Scan", "id"), null));
    assertVersionConflict(mappers.mapUnexpected(
        new RollbackException("rollback", new OptimisticLockException("stale")), null));
    assertVersionConflict(mappers.mapUnexpected(new ArcUndeclaredThrowableException(
        new jakarta.transaction.RollbackException("rb") {{ initCause(new StaleObjectStateException("Scan", "id")); }}),
        null));
  }

  @Test
  void otherFailuresStayInternalErrorsWithoutDetails() {
    Response response = mappers.mapUnexpected(new IllegalStateException("geheim detail"), null);
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(((Problem) response.getEntity()).detail()).doesNotContain("geheim");
  }
  @Test
  void loginLimitsPreserveProblemDetailsAndRetryAfter() {
    for (int status : java.util.List.of(429, 503)) {
      Response response = mappers.mapApi(new ApiException(status, "Te veel aanmeldpogingen",
          "Probeer het later opnieuw.").retryAfter(900), null);
      assertThat(response.getStatus()).isEqualTo(status);
      assertThat(response.getMediaType().toString()).isEqualTo("application/problem+json");
      assertThat(response.getHeaderString("Retry-After")).isEqualTo("900");
      assertThat(((Problem) response.getEntity()).title()).isEqualTo("Te veel aanmeldpogingen");
    }
  }

}
