package com.example.results.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfWriterRadarTest {

  @Test
  void drawsRadarWithLabelsForThreeOrMoreScores() throws Exception {
    byte[] pdf = new PdfWriter().heading("Scores per kennisgebied").radar(List.of(
            new PdfWriter.RadarPoint("Data-architectuur (3,40)", 3.4),
            new PdfWriter.RadarPoint("Datageletterdheid en specialistische competenties (2,00)", 2),
            new PdfWriter.RadarPoint("Datagedreven besluitvorming (4,67)", 4.67),
            new PdfWriter.RadarPoint("Databeleid (0,33)", 0.33)))
        .toBytes();

    String raw = new String(pdf, StandardCharsets.ISO_8859_1);
    assertThat(raw).contains("/GS1 gs", " c f");
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isEqualTo(1);
      String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
      assertThat(text).contains("Data-architectuur (3,40)", "Datagedreven besluitvorming (4,67)", "Databeleid (0,33)",
          "specialistische");
      new PDFRenderer(document).renderImage(0); // geldige tekenopdrachten
    }
  }

  @Test
  void drawsBarsForFewerThanThreeScores() {
    String raw = new String(new PdfWriter().radar(List.of(new PdfWriter.RadarPoint("A (2,50)", 2.5))).toBytes(),
        StandardCharsets.ISO_8859_1);
    assertThat(raw).contains(" re f").doesNotContain("/GS1 gs");
  }

  @Test
  void shortensLabelsLongerThanThreeLines() {
    List<String> lines = PdfWriter.labelLines(
        "Een uitzonderlijk lange naam voor een kennisgebied die echt niet op drie regels van een radarlabel past (1,00)",
        PdfWriter.Style.SMALL);
    assertThat(lines).hasSize(3);
    assertThat(lines.get(2)).endsWith("…");
  }

  @Test
  void radarMovesToNextPageWhenItDoesNotFit() throws Exception {
    PdfWriter writer = new PdfWriter();
    for (int index = 0; index < 45; index++) writer.text("Regel " + index);
    writer.radar(List.of(new PdfWriter.RadarPoint("A", 1), new PdfWriter.RadarPoint("B", 2),
        new PdfWriter.RadarPoint("C", 3)));
    try (PDDocument document = Loader.loadPDF(writer.toBytes())) {
      assertThat(document.getNumberOfPages()).isEqualTo(2);
    }
  }
}
