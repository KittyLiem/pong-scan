package com.example.results.application;

import com.example.organization.domain.OrganizationEntity;
import com.example.results.api.ResultsDtos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Nederlandstalige PDF-export met dezelfde gegevens als {@code GET /v1/scans/{id}/results}. */
@ApplicationScoped
public class ResultsExportService {
  public static final String GOALS_SECTION = "Organisatiedoelen en prioriteiten";
  private static final Locale NL = Locale.forLanguageTag("nl-NL");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm", NL)
      .withZone(ZoneId.of("Europe/Amsterdam"));

  private final ResultsService resultsService;

  public ResultsExportService(ResultsService resultsService) {
    this.resultsService = resultsService;
  }

  @Transactional
  public byte[] export(UUID memberId, UUID scanId) {
    return export(memberId, scanId, null);
  }

  @Transactional
  public byte[] export(UUID memberId, UUID scanId, Integer prioritySetRevision) {
    ResultsService.Computed computed = resultsService.compute(memberId, scanId, prioritySetRevision);
    ResultsDtos.ScanResults results = computed.results();
    OrganizationEntity organization = OrganizationEntity.findById(computed.scan().organizationId);

    PdfWriter pdf = new PdfWriter();
    pdf.title("Resultaten datavolwassenheidsscan");
    pdf.text("Organisatie: " + (organization == null ? "-" : organization.name));
    pdf.text("Scan: " + results.scanName() + " (revisie " + computed.scan().revision + ")");
    pdf.text("Beoordelingsperiode: " + results.assessmentPeriod());
    pdf.text("Afgerond op: " + (results.completedAt() == null ? "-" : DATE.format(results.completedAt())));

    pdf.heading("Totaalscore");
    pdf.text(number(results.totalScore()) + " van 5 - niveau " + results.maturityLevel().level() + " ("
        + results.maturityLevel().label() + ")");
    pdf.small("De totaalscore is het gemiddelde van alle beantwoorde vragen in de geselecteerde kennisgebieden.");

    pdf.heading("Scores per kennisgebied");
    for (ResultsDtos.DomainScore score : results.domainScores()) {
      pdf.text(score.domainName() + ": " + number(score.score()) + " - niveau " + score.maturityLevel().level()
          + " (" + score.maturityLevel().label() + ")");
    }

    pdf.heading("Sterktes, ontwikkelpunten en aanbevelingen");
    Map<UUID, ResultsDtos.DevelopmentPoint> pointsById = new HashMap<>();
    for (ResultsDtos.DevelopmentPoint point : results.developmentPoints()) {
      pointsById.put(point.id(), point);
      pdf.text(Recommendations.classificationLabel(point.classification()) + " - " + point.domainName());
      pdf.indented(point.text());
      pdf.indented("Aanbeveling: " + point.recommendation());
    }

    pdf.heading(GOALS_SECTION);
    pdf.text("Organisatiedoelen");
    if (results.goals().isEmpty()) {
      pdf.indented("Er zijn geen organisatiedoelen vastgelegd.");
    } else {
      for (ResultsDtos.OrganizationGoal goal : results.goals()) {
        pdf.indented("- " + goal.name() + " (prioriteit " + level(goal.priority()) + ")"
            + (goal.customText() == null ? "" : ": " + goal.customText()));
      }
    }
    pdf.text("Gekozen ontwikkelprioriteiten"
        + (results.priorities().setRevision() > 0 ? " (versie " + results.priorities().setRevision() + ")" : ""));
    var selected = results.priorities().items().stream().filter(ResultsDtos.PriorityItem::selected).toList();
    if (selected.isEmpty()) {
      pdf.indented("Er zijn nog geen ontwikkelprioriteiten gekozen.");
    } else {
      for (ResultsDtos.PriorityItem item : selected) {
        ResultsDtos.DevelopmentPoint point = pointsById.get(item.developmentPointId());
        String name = point == null ? item.developmentPointId().toString()
            : Recommendations.classificationLabel(point.classification()) + " " + point.domainName();
        pdf.indented("- " + name + ": impact " + level(item.impact()) + ", urgentie " + level(item.urgency()));
      }
    }
    pdf.blank();
    pdf.small("Doelen en prioriteiten zijn context bij de resultaten en beïnvloeden de scores niet.");
    return pdf.toBytes();
  }

  private static String number(double value) {
    return String.format(NL, "%.2f", value);
  }

  private static String level(String value) {
    return switch (value == null ? "" : value) {
      case "high" -> "hoog";
      case "medium" -> "middel";
      case "low" -> "laag";
      default -> value;
    };
  }
}
