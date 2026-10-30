package com.example.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.account.application.BootstrapAdmin;
import com.example.account.domain.MemberEntity;
import com.example.audit.AuditEventEntity;
import com.example.support.Api;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BootstrapAdminTest {
  @Inject BootstrapAdmin bootstrap;

  private static String role(String identifier) {
    return QuarkusTransaction.requiringNew().call(() ->
        MemberEntity.byIdentifier(identifier).map(member -> member.role).orElse(null));
  }

  @Test
  void createsAdminWhenIdentifierIsFree() {
    String identifier = Api.uniqueEmail("bootstrap");
    assertThat(bootstrap.bootstrap(identifier, "een-sterk-bootstrap-wachtwoord")).isEqualTo(BootstrapAdmin.Outcome.CREATED);
    assertThat(role(identifier)).isEqualTo("admin");
    String token = Api.login(identifier, "een-sterk-bootstrap-wachtwoord").getString("accessToken");
    Api.as(token).when().get("/v1/admin/domains").then().statusCode(200);
    long audits = QuarkusTransaction.requiringNew().call(() -> AuditEventEntity.count("action", "admin.bootstrap"));
    assertThat(audits).isPositive();
    // Tweede keer: niets te doen.
    assertThat(bootstrap.bootstrap(identifier, "een-sterk-bootstrap-wachtwoord"))
        .isEqualTo(BootstrapAdmin.Outcome.ALREADY_ADMIN);
  }

  @Test
  void neverPromotesPreRegisteredMember() {
    String identifier = Api.uniqueEmail("vooraf-geregistreerd");
    Api.register(identifier, "aanvaller-wachtwoord");
    assertThat(bootstrap.bootstrap(identifier, "een-sterk-bootstrap-wachtwoord"))
        .isEqualTo(BootstrapAdmin.Outcome.TAKEN_BY_MEMBER);
    assertThat(role(identifier)).isEqualTo("member");
    String token = Api.login(identifier, "aanvaller-wachtwoord").getString("accessToken");
    Api.as(token).when().get("/v1/admin/domains").then().statusCode(403);
  }

  @Test
  void requiresBothValuesAndStrongPassword() {
    assertThat(bootstrap.bootstrap(null, null)).isEqualTo(BootstrapAdmin.Outcome.NOT_CONFIGURED);
    assertThat(bootstrap.bootstrap(Api.uniqueEmail("half"), "")).isEqualTo(BootstrapAdmin.Outcome.NOT_CONFIGURED);
    assertThatThrownBy(() -> bootstrap.bootstrap(Api.uniqueEmail("kort"), "kort-ww"))
        .hasMessageContaining("minimaal 12");
  }
}
