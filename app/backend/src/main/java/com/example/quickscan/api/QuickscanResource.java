package com.example.quickscan.api;

import com.example.content.application.ActiveContentService;
import com.example.quickscan.application.QuickscanService;
import com.example.quickscan.application.QuickscanSettings;
import com.example.security.CurrentMember;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/quickscan")
@Produces(MediaType.APPLICATION_JSON)
public class QuickscanResource {
  public record QuickscanDomain(UUID id, String name, String description, int order, int questionCount) {}

  public record QuickscanDomains(List<QuickscanDomain> domains) {}

  private final QuickscanService service;
  private final QuickscanSettings settings;
  private final ActiveContentService content;
  private final CurrentMember currentMember;

  public QuickscanResource(QuickscanService service, QuickscanSettings settings, ActiveContentService content,
      CurrentMember currentMember) {
    this.service = service;
    this.settings = settings;
    this.content = content;
    this.currentMember = currentMember;
  }

  /** Openbaar: welke kennisgebieden de quickscan bevat, voor de landingspagina. */
  @GET
  @Path("/domains")
  @PermitAll
  @Operation(operationId = "getQuickscanDomains")
  public QuickscanDomains getQuickscanDomains() {
    settings.requireEnabled();
    return new QuickscanDomains(content.loadForQuickscan().stream()
        .map(item -> new QuickscanDomain(item.domain().id, item.domain().name, item.domain().description,
            item.domain().displayOrder, item.questions().size()))
        .toList());
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
