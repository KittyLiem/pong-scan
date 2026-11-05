package com.example.admin.api;

import com.example.admin.application.AdminQuickscanFollowupService;
import com.example.security.AdminOnly;
import com.example.security.CurrentMember;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

/** Handmatige opvolging van quickscan-accounts; verdwijnt (404) zodra de automatische e-mailprocedure draait. */
@Path("/v1/admin/quickscan-accounts")
@AdminOnly
@Produces(MediaType.APPLICATION_JSON)
public class AdminQuickscanResource {
  private final AdminQuickscanFollowupService service;
  private final CurrentMember currentMember;

  public AdminQuickscanResource(AdminQuickscanFollowupService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @GET
  @Operation(operationId = "listAdminQuickscanAccounts")
  public AdminQuickscanDtos.AccountCollection listAdminQuickscanAccounts(
      @QueryParam("status") String status,
      @QueryParam("cursor") String cursor,
      @QueryParam("pageSize") Integer pageSize) {
    return service.list(status, cursor, pageSize);
  }

  @POST
  @Path("/{memberId}/contacted")
  @Operation(operationId = "markAdminQuickscanAccountContacted")
  public AdminQuickscanDtos.Account markAdminQuickscanAccountContacted(@PathParam("memberId") UUID memberId) {
    return service.markContacted(currentMember.id(), memberId);
  }
}
