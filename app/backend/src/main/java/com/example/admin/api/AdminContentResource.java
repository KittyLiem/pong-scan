package com.example.admin.api;

import com.example.admin.application.AdminContentService;
import com.example.security.AdminOnly;
import com.example.security.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/admin")
@AdminOnly
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminContentResource {
  private final AdminContentService service;
  private final CurrentMember currentMember;

  public AdminContentResource(AdminContentService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @GET
  @Path("/domains")
  @Operation(operationId = "listAdminDomains")
  public AdminDtos.DomainCollection listAdminDomains(
      @QueryParam("cursor") String cursor, @QueryParam("pageSize") Integer pageSize) {
    return service.listDomains(cursor, pageSize);
  }

  @POST
  @Path("/domains")
  @Operation(operationId = "createAdminDomain")
  public Response createAdminDomain(
      @HeaderParam("X-Idempotency-Key") String idempotencyKey, @Valid @NotNull AdminDtos.DomainWrite input) {
    return Response.status(Response.Status.CREATED)
        .entity(service.createDomain(currentMember.id(), idempotencyKey, input)).build();
  }

  @PATCH
  @Path("/domains/{domainId}")
  @Operation(operationId = "updateAdminDomain")
  public AdminDtos.Domain updateAdminDomain(
      @PathParam("domainId") UUID domainId, @Valid @NotNull AdminDtos.DomainWrite input) {
    return service.updateDomain(currentMember.id(), domainId, input);
  }

  @POST
  @Path("/domains/{domainId}/questions")
  @Operation(operationId = "createAdminQuestion")
  public Response createAdminQuestion(
      @PathParam("domainId") UUID domainId,
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull AdminDtos.QuestionCreate input) {
    return Response.status(Response.Status.CREATED)
        .entity(service.createQuestion(currentMember.id(), domainId, idempotencyKey, input)).build();
  }

  @PATCH
  @Path("/questions/{questionId}")
  @Operation(operationId = "updateAdminQuestion")
  public AdminDtos.Question updateAdminQuestion(
      @PathParam("questionId") UUID questionId, @Valid @NotNull AdminDtos.QuestionUpdate input) {
    return service.updateQuestion(currentMember.id(), questionId, input);
  }

  @POST
  @Path("/questions/{questionId}/options")
  @Operation(operationId = "createAnswerOption")
  public Response createAnswerOption(
      @PathParam("questionId") UUID questionId,
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull AdminDtos.AnswerOptionWrite input) {
    return Response.status(Response.Status.CREATED)
        .entity(service.createOption(currentMember.id(), questionId, idempotencyKey, input)).build();
  }

  @PATCH
  @Path("/options/{optionId}")
  @Operation(operationId = "updateAnswerOption")
  public AdminDtos.AnswerOption updateAnswerOption(
      @PathParam("optionId") UUID optionId, @Valid @NotNull AdminDtos.AnswerOptionWrite input) {
    return service.updateOption(currentMember.id(), optionId, input);
  }
}
