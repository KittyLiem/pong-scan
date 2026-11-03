package com.example.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.example.support.Api;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Toegang tot organisaties en hun scans loopt via organization_members, niet via organizations.member_id. */
@QuarkusTest
class OrganizationMembershipTest {
  @Inject AgroalDataSource dataSource;

  @Test
  void demoOrganizationHasItsCreatorAsOwner() throws SQLException {
    assertThat(role(Api.DEMO_ORGANIZATION, "90000000-0000-0000-0000-000000000001")).isEqualTo("owner");
  }

  @Test
  void creatingAnOrganizationMakesTheCreatorOwner() throws SQLException {
    String token = Api.newMemberToken();
    String organizationId = Api.createOrganization(token);
    assertThat(role(organizationId, memberId(token))).isEqualTo("owner");
  }

  @Test
  void accessFollowsMembershipNotCreator() throws SQLException {
    Api.CompletedScan completed = Api.completedScan(1, index -> 3);
    String colleague = Api.newMemberToken();
    String colleagueId = memberId(colleague);

    Api.as(colleague).when().get("/v1/scans/{id}", completed.scanId()).then().statusCode(404);

    execute("insert into organization_members (organization_id, member_id, role, created_at) "
        + "values (?, ?, 'contributor', current_timestamp)", completed.organizationId(), colleagueId);
    Api.as(colleague).when().get("/v1/organizations").then().statusCode(200)
        .body("items.id", hasItem(completed.organizationId()));
    Api.as(colleague).when().get("/v1/scans/{id}/results", completed.scanId()).then().statusCode(200);
    Api.as(colleague).when().get("/v1/organizations/{id}/scans", completed.organizationId()).then().statusCode(200)
        .body("items[0].id", equalTo(completed.scanId()));

    // Zonder lidmaatschap geeft ook de aanmaker van de organisatie geen toegang meer.
    execute("delete from organization_members where organization_id = ? and member_id <> ?",
        completed.organizationId(), colleagueId);
    Api.as(completed.token()).when().get("/v1/scans/{id}", completed.scanId()).then().statusCode(404);
    Api.as(completed.token()).when().get("/v1/organizations").then().statusCode(200)
        .body("items.id", not(hasItem(completed.organizationId())));
  }

  private static String memberId(String token) {
    return Api.as(token).when().get("/v1/me").then().statusCode(200).extract().path("id");
  }

  private String role(String organizationId, String memberId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(
            "select role from organization_members where organization_id = ? and member_id = ?")) {
      statement.setObject(1, UUID.fromString(organizationId));
      statement.setObject(2, UUID.fromString(memberId));
      try (var rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private void execute(String sql, String organizationId, String memberId) throws SQLException {
    try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(organizationId));
      statement.setObject(2, UUID.fromString(memberId));
      statement.executeUpdate();
    }
  }
}
