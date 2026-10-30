package com.example.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BoundedQueriesTest {
  @Inject EntityManagerFactory entityManagerFactory;

  @Test
  void contentAndResultsQueryCountsStayBoundedWithSevenSelectedDomains() {
    Api.CompletedScan one = Api.completedScan(1, index -> 2);
    Api.CompletedScan seven = Api.completedScan(7, index -> 2);
    assertThat(Api.scan(one.token(), one.scanId()).getList("selectedDomainIds")).hasSize(1);
    assertThat(Api.scan(seven.token(), seven.scanId()).getList("selectedDomainIds")).hasSize(7);
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    assertThat(statistics.isStatisticsEnabled()).isTrue();
    for (String endpoint : List.of("content", "results")) {
      long oneCount = statementCount(statistics, one, endpoint);
      long sevenCount = statementCount(statistics, seven, endpoint);
      assertThat(oneCount).as("GET /%s met één domein", endpoint).isBetween(1L, 15L);
      assertThat(sevenCount).as("GET /%s met zeven domeinen", endpoint).isBetween(1L, 15L);
      assertThat(sevenCount).as("GET /%s: één domein=%s, zeven domeinen=%s", endpoint, oneCount, sevenCount)
          .isEqualTo(oneCount);
    }
  }

  private static long statementCount(Statistics statistics, Api.CompletedScan scan, String endpoint) {
    statistics.clear();
    Api.as(scan.token()).when().get("/v1/scans/{id}/" + endpoint, scan.scanId())
        .then().statusCode(200).contentType("application/json");
    return statistics.getPrepareStatementCount();
  }
}
