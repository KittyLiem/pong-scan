package com.example.account.api;

import com.example.account.application.AccountService;
import com.example.security.CurrentMember;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {
  private final AccountService service;
  private final CurrentMember currentMember;

  public AccountResource(AccountService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @POST
  @Path("/auth/registrations")
  @PermitAll
  @Operation(operationId = "registerMember")
  public Response registerMember(
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull AccountDtos.RegistrationInput input) {
    AccountDtos.Member member = service.register(idempotencyKey, input);
    return Response.created(URI.create("/v1/me")).entity(member).build();
  }

  @POST
  @Path("/auth/sessions")
  @PermitAll
  @Operation(operationId = "createSession")
  public Response createSession(
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull AccountDtos.SessionInput input) {
    return Response.status(Response.Status.CREATED).entity(service.createSession(idempotencyKey, input)).build();
  }

  @DELETE
  @Path("/auth/sessions/current")
  @Operation(operationId = "deleteCurrentSession")
  public Response deleteCurrentSession() {
    service.revokeSession(currentMember.id(), currentMember.sessionId());
    return Response.noContent().build();
  }

  @GET
  @Path("/me")
  @Operation(operationId = "getCurrentMember")
  public AccountDtos.Member getCurrentMember() {
    return service.me(currentMember.id());
  }
}
