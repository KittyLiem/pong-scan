package com.example.content.api;

import com.example.common.ApiException;
import com.example.content.application.ActiveContentService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/v1/models/active")
@Produces(MediaType.APPLICATION_JSON)
public class ActiveModelResource {
  public record ModelDomain(UUID id, String name, String description, int order, boolean required, int questionCount) {}

  public record ActiveModel(List<ModelDomain> domains) {}

  private final ActiveContentService content;

  public ActiveModelResource(ActiveContentService content) {
    this.content = content;
  }

  @GET
  @Operation(operationId = "getActiveModel")
  public ActiveModel getActiveModel() {
    List<ActiveContentService.ActiveDomain> domains = content.load();
    if (domains.isEmpty()) {
      throw ApiException.notFound("Er is geen actieve inhoud beschikbaar voor een nieuwe scan.");
    }
    return new ActiveModel(domains.stream()
        .map(item -> new ModelDomain(item.domain().id, item.domain().name, item.domain().description,
            item.domain().displayOrder, item.domain().required, item.questions().size()))
        .toList());
  }
}
