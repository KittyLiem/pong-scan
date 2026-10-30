package com.example.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.ApiException;
import com.example.priority.api.DevelopmentPriorityDtos;
import com.example.priority.application.DevelopmentPriorityService;
import com.example.results.application.ResultsExportService;
import com.example.results.application.ResultsService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ResultsHistoryTest {
  private static final UUID MEMBER = UUID.fromString("90000000-0000-0000-0000-000000000001");
  private static final UUID SCAN = UUID.fromString("91000000-0000-0000-0000-000000000001");
  @Inject ResultsService results;
  @Inject DevelopmentPriorityService priorities;
  @Inject ResultsExportService export;

  @Test
  @TestTransaction
  void emptyHistoricalSetsRemainAvailableInResultsAndPdf() {
    var before = results.get(MEMBER, SCAN);
    var first = priorities.save(MEMBER, SCAN, new DevelopmentPriorityDtos.PrioritiesInput(List.of()));
    var second = priorities.save(MEMBER, SCAN, new DevelopmentPriorityDtos.PrioritiesInput(List.of()));
    var historical = results.get(MEMBER, SCAN, first.setRevision());
    assertThat(historical.priorities().setRevision()).isEqualTo(first.setRevision());
    assertThat(historical.priorities().items()).isEmpty();
    assertThat(historical.availablePrioritySetRevisions()).contains(first.setRevision(), second.setRevision());
    assertThat(results.get(MEMBER, SCAN).priorities().setRevision()).isEqualTo(second.setRevision());
    assertThat(historical.totalScore()).isEqualTo(before.totalScore());
    assertThat(new String(export.export(MEMBER, SCAN, first.setRevision()), StandardCharsets.ISO_8859_1))
        .contains("versie " + first.setRevision());
  }

  @Test
  @TestTransaction
  void unknownRevisionIsNotFoundForResultsAndExport() {
    assertThatThrownBy(() -> results.get(MEMBER, SCAN, 999))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
    assertThatThrownBy(() -> export.export(MEMBER, SCAN, 999))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
  }
}
