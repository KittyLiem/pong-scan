package com.example.scan.api;

import com.example.scan.application.AnswerService;
import com.example.scan.application.ScanContentService;
import com.example.scan.application.ScanService;
import com.example.security.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/scans/{scanId}")
@Produces(MediaType.APPLICATION_JSON)
public class ScanResource {
  private final ScanService scans;
  private final ScanContentService content;
  private final AnswerService answers;
  private final CurrentMember currentMember;

  public ScanResource(ScanService scans, ScanContentService content, AnswerService answers,
      CurrentMember currentMember) {
    this.scans = scans;
    this.content = content;
    this.answers = answers;
    this.currentMember = currentMember;
  }

  @GET
  @Operation(operationId = "getScan")
  public ScanDtos.Scan getScan(@PathParam("scanId") UUID scanId) {
    return scans.get(currentMember.id(), scanId);
  }

  @PATCH
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "updateScan")
  public ScanDtos.Scan updateScan(@PathParam("scanId") UUID scanId, @Valid @NotNull ScanDtos.ScanUpdateInput input) {
    return scans.update(currentMember.id(), scanId, input);
  }

  @GET
  @Path("/content")
  @Operation(operationId = "getScanContent")
  public ScanDtos.ScanContent getScanContent(@PathParam("scanId") UUID scanId) {
    return content.content(currentMember.id(), scanId);
  }

  @PUT
  @Path("/answers/{questionId}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "saveAnswer")
  public ScanDtos.SavedAnswer saveAnswer(
      @PathParam("scanId") UUID scanId,
      @PathParam("questionId") UUID questionId,
      @Valid @NotNull ScanDtos.AnswerInput input) {
    return answers.save(currentMember.id(), scanId, questionId, input);
  }

  @POST
  @Path("/completion")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "completeScan")
  public ScanDtos.Scan completeScan(
      @PathParam("scanId") UUID scanId,
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull ScanDtos.CompletionInput input) {
    return scans.complete(currentMember.id(), scanId, idempotencyKey, input);
  }

  @POST
  @Path("/revisions")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(operationId = "createScanRevision")
  public Response createScanRevision(
      @PathParam("scanId") UUID scanId,
      @HeaderParam("X-Idempotency-Key") String idempotencyKey,
      @Valid @NotNull ScanDtos.RevisionInput input) {
    ScanDtos.Scan revision = scans.createRevision(currentMember.id(), scanId, idempotencyKey, input);
    return Response.created(URI.create("/v1/scans/" + revision.id())).entity(revision).build();
  }
}
