package com.example.results.api;

import com.example.results.application.ResultsExportService;
import com.example.results.application.ResultsService;
import com.example.security.CurrentMember;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/scans/{scanId}")
public class ResultsResource {
  private final ResultsService results;
  private final ResultsExportService export;
  private final CurrentMember currentMember;

  public ResultsResource(ResultsService results, ResultsExportService export, CurrentMember currentMember) {
    this.results = results;
    this.export = export;
    this.currentMember = currentMember;
  }

  @GET
  @Path("/results")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(operationId = "getResults")
  public ResultsDtos.ScanResults getResults(@PathParam("scanId") UUID scanId,
      @QueryParam("prioritySetRevision") @Min(1) Integer prioritySetRevision) {
    return results.get(currentMember.id(), scanId, prioritySetRevision);
  }

  @GET
  @Path("/export")
  @Produces({"application/pdf", "application/problem+json"})
  @Operation(operationId = "exportScanPdf")
  public Response exportScanPdf(@PathParam("scanId") UUID scanId,
      @QueryParam("prioritySetRevision") @Min(1) Integer prioritySetRevision) {
    byte[] pdf = export.export(currentMember.id(), scanId, prioritySetRevision);
    return Response.ok(pdf, "application/pdf")
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"scan-" + scanId + ".pdf\"")
        .build();
  }
}
