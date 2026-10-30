package com.example.organization.api;

import com.example.organization.application.OrganizationService;
import com.example.security.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/organizations")
@Produces(MediaType.APPLICATION_JSON)
public class OrganizationResource {
  private final OrganizationService service;
  private final CurrentMember currentMember;

  public OrganizationResource(OrganizationService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @GET
  @Operation(operationId = "listOrganizations")
  public OrganizationDtos.OrganizationCollection listOrganizations() {
    return service.list(currentMember.id());
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "createOrganization")
  public Response createOrganization(
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull OrganizationDtos.OrganizationInput input) {
    OrganizationDtos.Organization organization = service.create(currentMember.id(), idempotencyKey, input);
    return Response.created(URI.create("/v1/organizations/" + organization.id())).entity(organization).build();
  }
}
