package com.example.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class OrderingConstraintsTest {
  @Inject AgroalDataSource dataSource;

  @ParameterizedTest
  @ValueSource(strings = {"domains:display_order", "questions:display_order", "answer_options:display_order",
      "scan_snapshot_domains:display_order", "scan_snapshot_questions:display_order",
      "scan_snapshot_options:display_order", "scan_goals:position", "scan_answer_evidence:position"})
  void negativeOrderingIsRejectedByDatabase(String target) throws SQLException {
    String[] parts = target.split(":");
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      if ("scan_answer_evidence".equals(parts[0])) {
        assertThatThrownBy(() -> statement.executeUpdate("insert into scan_answer_evidence "
            + "(answer_id, position, evidence_reference) select id, -1, 'test' from scan_answers fetch first 1 row only"))
            .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23513"));
        return;
      }
      try (var rows = statement.executeQuery("select count(*) from " + parts[0])) {
        rows.next();
        assertThat(rows.getInt(1)).isPositive();
      }
      assertThatThrownBy(() -> statement.executeUpdate("update " + parts[0] + " set " + parts[1] + " = -1"))
          .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23513"));
    }
  }

  @Test
  void selectedDomainLookupHasAnIndexStartingWithSnapshotDomain() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var indexes = connection.getMetaData().getIndexInfo(null, null, "SCAN_SELECTED_DOMAINS", false, false)) {
      boolean found = false;
      while (indexes.next()) {
        found |= "SNAPSHOT_DOMAIN_ID".equals(indexes.getString("COLUMN_NAME"))
            && indexes.getShort("ORDINAL_POSITION") == 1;
      }
      assertThat(found).isTrue();
    }
  }
}
