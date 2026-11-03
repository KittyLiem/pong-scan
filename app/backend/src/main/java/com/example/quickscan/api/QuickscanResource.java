package com.example.quickscan.api;

import com.example.quickscan.application.QuickscanService;
import com.example.security.CurrentMember;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/quickscan")
@Produces(MediaType.APPLICATION_JSON)
public class QuickscanResource {
  private final QuickscanService service;
  private final CurrentMember currentMember;

  public QuickscanResource(QuickscanService service, CurrentMember currentMember) {
    this.service = service;
    this.currentMember = currentMember;
  }

  /** Idempotent: opent de bestaande quickscan (200) of maakt hem aan (201). */
  @PUT
  @Operation(operationId = "openQuickscan")
  public Response openQuickscan() {
    QuickscanService.Opened opened = service.open(currentMember.id());
    if (!opened.created()) {
      return Response.ok(opened.scan()).build();
    }
    return Response.created(URI.create("/v1/scans/" + opened.scan().id())).entity(opened.scan()).build();
  }
}
