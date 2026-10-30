package com.example.priority.api;

import com.example.priority.application.DevelopmentPriorityService;
import com.example.results.api.ResultsDtos;
import com.example.security.CurrentMember;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/scans/{scanId}/development-priorities")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DevelopmentPriorityResource {
  private final DevelopmentPriorityService service;
  private final CurrentMember currentMember;

  public DevelopmentPriorityResource(DevelopmentPriorityService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  @PUT
  @Operation(operationId = "saveDevelopmentPriorities")
  public ResultsDtos.ActionOverview saveDevelopmentPriorities(
      @PathParam("scanId") UUID scanId, @Valid @NotNull DevelopmentPriorityDtos.PrioritiesInput input) {
    return service.save(currentMember.id(), scanId, input);
  }
}
