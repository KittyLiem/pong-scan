package com.example.results.application;

import java.util.Locale;
import java.util.Map;

/** Nederlandstalige teksten en concrete aanbevelingen per kennisgebied en classificatie. */
public final class Recommendations {
  private Recommendations() {}

  public static final String STRENGTH = "strength";
  public static final String DEVELOPMENT_POINT = "development-point";
  public static final String GAP = "gap";

  private static final String[] LABELS = {
      "Niet ingericht", "Ad hoc", "Herhaalbaar", "Gedefinieerd", "Beheerst", "Optimaliserend"};

  /** Sterkte vanaf 4,0; gap onder 2,5; daartussen ontwikkelpunt. */
  public static String classify(double score) {
    if (score >= 4.0) return STRENGTH;
    if (score < 2.5) return GAP;
    return DEVELOPMENT_POINT;
  }

  public static int level(double score) {
    return (int) Math.max(0, Math.min(5, Math.floor(score)));
  }

  public static String label(int level) {
    return LABELS[Math.max(0, Math.min(5, level))];
  }

  public static String classificationLabel(String classification) {
    return switch (classification) {
      case STRENGTH -> "Sterkte";
      case GAP -> "Knelpunt";
      default -> "Ontwikkelpunt";
    };
  }

  public static String text(String domainName, double score, String classification) {
    String formatted = String.format(Locale.forLanguageTag("nl-NL"), "%.2f", score);
    String level = label(level(score));
    return switch (classification) {
      case STRENGTH -> domainName + " is een sterkte: met een score van " + formatted + " (" + level
          + ") is dit kennisgebied aantoonbaar goed ingericht.";
      case GAP -> domainName + " is een duidelijk knelpunt: met een score van " + formatted + " (" + level
          + ") ontbreekt een structurele aanpak.";
      default -> domainName + " is een ontwikkelpunt: met een score van " + formatted + " (" + level
          + ") is de basis aanwezig, maar nog niet overal geborgd.";
    };
  }

  private static final Map<String, String[]> BY_DOMAIN = Map.of(
      "datastrategie", new String[] {
          "Gebruik de datastrategie actief als toetssteen bij investeringsbesluiten en deel successen organisatiebreed.",
          "Koppel ieder datainitiatief aan een organisatiedoel met meetbare doelstellingen en evalueer deze jaarlijks.",
          "Stel samen met de directie een beknopte datavisie op met drie tot vijf prioriteiten voor de komende twee jaar."},
      "datakwaliteit", new String[] {
          "Breid de kwaliteitsmetingen uit naar ketenpartners en automatiseer signalering bij afwijkingen.",
          "Leg kwaliteitseisen vast voor alle kritieke gegevenssets en publiceer maandelijks een kwaliteitsdashboard.",
          "Kies de vijf meest kritieke gegevenssets, benoem kwaliteitseisen en start met een eenvoudige periodieke meting."},
      "governance", new String[] {
          "Borg de governance in de planning-en-controlcyclus en toets periodiek of rollen nog passen.",
          "Formaliseer het mandaat van data-eigenaren en stewards en richt een vast datagovernance-overleg in.",
          "Benoem voor de belangrijkste gegevens een data-eigenaar en leg basisafspraken over gebruik en toegang vast."},
      "technologie", new String[] {
          "Standaardiseer bewezen platformcomponenten en maak ze beschikbaar als herbruikbare bouwstenen.",
          "Werk de data-architectuur uit tot concrete standaarden voor integratie, opslag en toegangsbeheer.",
          "Breng de huidige systemen en datastromen in kaart en bepaal welke koppelingen het eerst moeten worden verbeterd."},
      "processen", new String[] {
          "Gebruik procesindicatoren om dataprocessen continu te verbeteren en deel verbeteringen tussen afdelingen.",
          "Beschrijf de belangrijkste dataprocessen eenduidig en wijs per proces een verantwoordelijke aan.",
          "Start met het beschrijven van het meest kritieke dataproces, inclusief rollen, stappen en bewaartermijnen."},
      "vaardigheden", new String[] {
          "Zet interne experts in als coach en veranker datavaardigheden in functieprofielen.",
          "Stel een opleidingsplan op met basistrainingen datageletterdheid voor alle medewerkers en verdieping voor sleutelrollen.",
          "Inventariseer welke datavaardigheden ontbreken en organiseer een eerste basistraining voor leidinggevenden."},
      "datagedreven besluitvorming", new String[] {
          "Veranker datagedreven experimenten in de beleidscyclus en evalueer besluiten structureel achteraf.",
          "Leg eenduidige definities vast voor stuurinformatie en gebruik één set dashboards in alle overleggen.",
          "Kies drie terugkerende besluiten en onderbouw deze voortaan expliciet met een beperkte set kernindicatoren."});

  public static String recommendation(String domainName, String classification) {
    int index = switch (classification) {
      case STRENGTH -> 0;
      case DEVELOPMENT_POINT -> 1;
      default -> 2;
    };
    String[] specific = BY_DOMAIN.get(domainName == null ? "" : domainName.trim().toLowerCase(Locale.ROOT));
    if (specific != null) return specific[index];
    return switch (classification) {
      case STRENGTH -> "Behoud het niveau van " + domainName
          + ", deel de aanpak als goed voorbeeld en toets jaarlijks of het nog aansluit.";
      case DEVELOPMENT_POINT -> "Leg de werkwijze voor " + domainName
          + " vast, beleg eigenaarschap en meet ieder kwartaal de voortgang.";
      default -> "Start voor " + domainName
          + " met een kleine, concrete verbeteractie met een eigenaar en een termijn van drie maanden.";
    };
  }
}
