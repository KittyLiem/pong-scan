package com.example.scan.api;

import com.example.scan.application.ScanService;
import com.example.security.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/organizations/{organizationId}/scans")
@Produces(MediaType.APPLICATION_JSON)
public class ScanPortfolioResource {
  private final ScanService service;
  private final CurrentMember currentMember;

  public ScanPortfolioResource(ScanService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @GET
  @Operation(operationId = "listScans")
  public ScanDtos.ScanCollection listScans(
      @PathParam("organizationId") UUID organizationId,
      @QueryParam("cursor") String cursor,
      @QueryParam("pageSize") Integer pageSize) {
    return service.list(currentMember.id(), organizationId, cursor, pageSize);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "createScan")
  public Response createScan(
      @PathParam("organizationId") UUID organizationId,
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull ScanDtos.ScanCreateInput input) {
    ScanDtos.Scan scan = service.create(currentMember.id(), organizationId, idempotencyKey, input);
    return Response.created(URI.create("/v1/scans/" + scan.id())).entity(scan).build();
  }
}
