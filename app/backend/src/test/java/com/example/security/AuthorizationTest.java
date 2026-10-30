package com.example.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.example.account.api.AccountResource;
import com.example.admin.api.AdminContentResource;
import com.example.content.api.ActiveModelResource;
import com.example.organization.api.OrganizationResource;
import com.example.priority.api.DevelopmentPriorityResource;
import com.example.results.api.ResultsResource;
import com.example.scan.api.ScanPortfolioResource;
import com.example.scan.api.ScanResource;
import com.example.support.Api;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deny-by-default: zonder token 401 op iedere niet-publieke endpoint, niet-beheerder 403 op beheer. */
@QuarkusTest
class AuthorizationTest {
  private static final String ID = UUID.randomUUID().toString();

  record Endpoint(String method, String path) {}

  static final List<Endpoint> PROTECTED = List.of(
      new Endpoint("GET", "/v1/me"),
      new Endpoint("DELETE", "/v1/auth/sessions/current"),
      new Endpoint("GET", "/v1/organizations"),
      new Endpoint("POST", "/v1/organizations"),
      new Endpoint("GET", "/v1/models/active"),
      new Endpoint("GET", "/v1/organizations/" + ID + "/scans"),
      new Endpoint("POST", "/v1/organizations/" + ID + "/scans"),
      new Endpoint("GET", "/v1/scans/" + ID),
      new Endpoint("PATCH", "/v1/scans/" + ID),
      new Endpoint("GET", "/v1/scans/" + ID + "/content"),
      new Endpoint("PUT", "/v1/scans/" + ID + "/answers/" + ID),
      new Endpoint("POST", "/v1/scans/" + ID + "/completion"),
      new Endpoint("POST", "/v1/scans/" + ID + "/revisions"),
      new Endpoint("GET", "/v1/scans/" + ID + "/results"),
      new Endpoint("GET", "/v1/scans/" + ID + "/export"),
      new Endpoint("PUT", "/v1/scans/" + ID + "/development-priorities"));

  static final List<Endpoint> ADMIN = List.of(
      new Endpoint("GET", "/v1/admin/domains"),
      new Endpoint("POST", "/v1/admin/domains"),
      new Endpoint("PATCH", "/v1/admin/domains/" + ID),
      new Endpoint("POST", "/v1/admin/domains/" + ID + "/questions"),
      new Endpoint("PATCH", "/v1/admin/questions/" + ID),
      new Endpoint("POST", "/v1/admin/questions/" + ID + "/options"),
      new Endpoint("PATCH", "/v1/admin/options/" + ID));

  @Test
  void everyProtectedEndpointRequiresBearerToken() {
    List<Endpoint> all = new ArrayList<>(PROTECTED);
    all.addAll(ADMIN);
    for (Endpoint endpoint : all) {
      given().contentType(ContentType.JSON).header("X-Idempotency-Key", Api.key()).body("{}")
          .when().request(endpoint.method(), endpoint.path())
          .then().statusCode(401).contentType("application/problem+json")
          .body("status", equalTo(401)).body("title", equalTo("Niet aangemeld"))
          .header("X-Correlation-Id", notNullValue());
    }
  }

  @Test
  void garbageTokenIsRejectedEverywhere() {
    for (Endpoint endpoint : PROTECTED) {
      given().contentType(ContentType.JSON).header("Authorization", "Bearer abc.def.ghi").body("{}")
          .when().request(endpoint.method(), endpoint.path())
          .then().statusCode(401).contentType("application/problem+json");
    }
  }

  @Test
  void nonAdminGetsForbiddenOnEveryAdminEndpoint() {
    String token = Api.newMemberToken();
    for (Endpoint endpoint : ADMIN) {
      Api.as(token).header("X-Idempotency-Key", Api.key()).body("{}")
          .when().request(endpoint.method(), endpoint.path())
          .then().statusCode(403).contentType("application/problem+json")
          .body("title", equalTo("Geen toegang"));
    }
  }

  @Test
  void adminCanReachAdminEndpoints() {
    Api.as(Api.adminToken()).when().get("/v1/admin/domains").then().statusCode(200);
  }

  /**
   * Structurele borging: alleen registratie en sessie aanmaken zijn publiek. Een nieuwe endpoint zonder annotatie
   * valt automatisch onder de authenticatie (deny-by-default) — dit test de filterlogica direct.
   */
  @Test
  void onlyRegistrationAndSessionCreationArePublic() throws Exception {
    List<String> publicMethods = new ArrayList<>();
    for (Class<?> resource : List.of(AccountResource.class, OrganizationResource.class, ActiveModelResource.class,
        ScanPortfolioResource.class, ScanResource.class, ResultsResource.class, DevelopmentPriorityResource.class,
        AdminContentResource.class)) {
      for (Method method : resource.getDeclaredMethods()) {
        boolean endpoint = method.isAnnotationPresent(GET.class) || method.isAnnotationPresent(POST.class)
            || method.isAnnotationPresent(PUT.class) || method.isAnnotationPresent(PATCH.class)
            || method.isAnnotationPresent(DELETE.class);
        if (endpoint && AuthenticationFilter.isPublic(method, resource)) {
          publicMethods.add(method.getName());
        }
        assertThat(resource.isAnnotationPresent(PermitAll.class)).isFalse();
      }
    }
    assertThat(publicMethods).containsExactlyInAnyOrder("registerMember", "createSession");

    class UnannotatedResource {
      @GET
      public String nieuw() {
        return "";
      }
    }
    Method unannotated = UnannotatedResource.class.getDeclaredMethod("nieuw");
    assertThat(AuthenticationFilter.isPublic(unannotated, UnannotatedResource.class)).isFalse();
    assertThat(AuthenticationFilter.requiresAdmin(
        AdminContentResource.class.getDeclaredMethod("listAdminDomains", String.class, Integer.class),
        AdminContentResource.class)).isTrue();
  }
}
