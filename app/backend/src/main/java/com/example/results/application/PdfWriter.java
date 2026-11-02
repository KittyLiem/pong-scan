package com.example.results.application;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimale, afhankelijkheidsvrije PDF-writer: A4, Helvetica/Helvetica-Bold met WinAnsiEncoding (zodat é, ë, ï
 * enz. correct worden weergegeven), automatische regelafbreking en paginering met paginanummers. Daarnaast een
 * radargrafiek (vectorafbeelding) van scores op een schaal van 0–5, gelijk aan die in de webapplicatie.
 */
public final class PdfWriter {
  private static final Charset WIN_ANSI = Charset.forName("windows-1252");
  private static final float PAGE_WIDTH = 595;
  private static final float PAGE_HEIGHT = 842;
  private static final float MARGIN = 50;
  private static final float BOTTOM = 60;
  private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
  private static final double MAX_SCORE = 5;
  private static final float RADAR_RADIUS = 95;
  private static final float RADAR_LABEL_GAP = 10;
  private static final int RADAR_LABEL_MAX_LINES = 3;
  private static final float RADAR_LABEL_WIDTH = CONTENT_WIDTH / 2 - RADAR_RADIUS - RADAR_LABEL_GAP - 8;
  private static final float BAR_ROW_HEIGHT = 30;
  /** Kleuren uit het thema: --bpc-border en --bpc-primary. */
  private static final String BORDER_RGB = "0.851 0.824 0.882";
  private static final String PRIMARY_RGB = "0.357 0.173 0.514";

  public enum Style {
    TITLE(18, true, 26), HEADING(13, true, 22), BODY(10, false, 14), SMALL(9, false, 12);

    final float size;
    final boolean bold;
    final float leading;

    Style(float size, boolean bold, float leading) {
      this.size = size;
      this.bold = bold;
      this.leading = leading;
    }
  }

  /** Eén score in de radargrafiek; het label wordt getoond zoals aangeleverd. */
  public record RadarPoint(String label, double score) {}

  private sealed interface Element permits Line, Radar {}

  private record Line(Style style, String text, float indent) implements Element {}

  /** Radar bij drie of meer scores; anders horizontale staven, net als in de webapplicatie. */
  private record Radar(List<RadarPoint> points) implements Element {
    boolean bars() {
      return points.size() < 3;
    }

    float height() {
      return bars() ? points.size() * BAR_ROW_HEIGHT + 8
          : 2 * (RADAR_RADIUS + RADAR_LABEL_GAP + RADAR_LABEL_MAX_LINES * Style.SMALL.leading + 4);
    }
  }

  private final List<Element> lines = new ArrayList<>();

  public PdfWriter title(String text) {
    return add(Style.TITLE, text, 0);
  }

  public PdfWriter heading(String text) {
    lines.add(new Line(Style.BODY, "", 0));
    return add(Style.HEADING, text, 0);
  }

  public PdfWriter text(String text) {
    return add(Style.BODY, text, 0);
  }

  public PdfWriter indented(String text) {
    return add(Style.BODY, text, 16);
  }

  public PdfWriter small(String text) {
    return add(Style.SMALL, text, 0);
  }

  /** Radargrafiek van scores (0–5); bij minder dan drie scores worden staven getoond. */
  public PdfWriter radar(List<RadarPoint> points) {
    if (!points.isEmpty()) lines.add(new Radar(List.copyOf(points)));
    return this;
  }

  public PdfWriter blank() {
    lines.add(new Line(Style.BODY, "", 0));
    return this;
  }

  private PdfWriter add(Style style, String text, float indent) {
    float available = PAGE_WIDTH - 2 * MARGIN - indent;
    for (String paragraph : (text == null ? "" : text).split("\\R", -1)) {
      for (String wrapped : wrap(paragraph, style, available)) {
        lines.add(new Line(style, wrapped, indent));
      }
    }
    return this;
  }

  static List<String> wrap(String text, Style style, float maxWidth) {
    List<String> result = new ArrayList<>();
    if (text.isEmpty()) {
      result.add("");
      return result;
    }
    StringBuilder current = new StringBuilder();
    for (String word : text.split(" ")) {
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (width(candidate, style) <= maxWidth) {
        current.setLength(0);
        current.append(candidate);
        continue;
      }
      if (!current.isEmpty()) {
        result.add(current.toString());
        current.setLength(0);
      }
      // Woord langer dan een regel: hard afbreken.
      String rest = word;
      while (width(rest, style) > maxWidth && rest.length() > 1) {
        int cut = rest.length();
        while (cut > 1 && width(rest.substring(0, cut), style) > maxWidth) cut--;
        result.add(rest.substring(0, cut));
        rest = rest.substring(cut);
      }
      current.append(rest);
    }
    if (!current.isEmpty()) result.add(current.toString());
    return result;
  }

  /** Benadering van de Helvetica-breedte (AFM-gemiddelden) in punten. */
  static float width(String text, Style style) {
    float units = 0;
    for (char c : text.toCharArray()) {
      if ("il.,:;'|!".indexOf(c) >= 0) units += 260;
      else if ("fjtrI() ".indexOf(c) >= 0) units += 320;
      else if ("mwMW".indexOf(c) >= 0) units += 860;
      else if (Character.isUpperCase(c)) units += 680;
      else units += 560;
    }
    return units * style.size / 1000f * (style.bold ? 1.06f : 1f);
  }

  public byte[] toBytes() {
    List<StringBuilder> pages = new ArrayList<>();
    StringBuilder page = null;
    float y = 0;
    for (Element element : lines) {
      if (element instanceof Radar radar) {
        if (page == null || y - radar.height() < BOTTOM) {
          page = new StringBuilder();
          pages.add(page);
          y = PAGE_HEIGHT - MARGIN;
        }
        if (radar.bars()) drawBars(page, radar.points(), y);
        else drawRadar(page, radar.points(), y - radar.height() / 2);
        y -= radar.height();
        continue;
      }
      Line line = (Line) element;
      if (page == null || y - line.style.leading < BOTTOM) {
        page = new StringBuilder();
        pages.add(page);
        y = PAGE_HEIGHT - MARGIN;
        if (line.text.isEmpty()) continue;
      }
      y -= line.style.leading;
      if (line.text.isEmpty()) continue;
      page.append("BT /").append(line.style.bold ? "F2" : "F1").append(' ')
          .append(fmt(line.style.size)).append(" Tf ")
          .append(fmt(MARGIN + line.indent)).append(' ').append(fmt(y)).append(" Td (")
          .append(encode(line.text)).append(") Tj ET\n");
    }
    if (pages.isEmpty()) pages.add(new StringBuilder());
    int pageCount = pages.size();
    for (int index = 0; index < pageCount; index++) {
      pages.get(index).append("BT /F1 8 Tf ").append(fmt(MARGIN)).append(' ').append(fmt(30)).append(" Td (")
          .append(encode("Pagina " + (index + 1) + " van " + pageCount)).append(") Tj ET\n");
    }

    // Objectnummers: 1 catalog, 2 pages, 3 F1, 4 F2, daarna per pagina (page, content).
    List<String> objects = new ArrayList<>();
    StringBuilder kids = new StringBuilder();
    for (int index = 0; index < pageCount; index++) {
      kids.append(5 + index * 2).append(" 0 R ");
    }
    objects.add("<< /Type /Catalog /Pages 2 0 R >>");
    objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>");
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
    for (int index = 0; index < pageCount; index++) {
      int contentNumber = 6 + index * 2;
      objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> /ExtGState << /GS1 << /Type /ExtGState /ca 0.25 >> >> >> /Contents "
          + contentNumber + " 0 R >>");
      String stream = pages.get(index).toString();
      objects.add("<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream
          + "endstream");
    }

    StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
    List<Integer> offsets = new ArrayList<>();
    for (int index = 0; index < objects.size(); index++) {
      offsets.add(pdf.length());
      pdf.append(index + 1).append(" 0 obj\n").append(objects.get(index)).append("\nendobj\n");
    }
    int xref = pdf.length();
    pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
    offsets.forEach(offset -> pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset)));
    pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
        .append(xref).append("\n%%EOF\n");
    return pdf.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private static void drawRadar(StringBuilder page, List<RadarPoint> points, float centerY) {
    float centerX = MARGIN + CONTENT_WIDTH / 2;
    int count = points.size();
    page.append("q ").append(BORDER_RGB).append(" RG 0.75 w\n");
    for (int level = 1; level <= MAX_SCORE; level++) {
      float[][] ring = new float[count][];
      for (int index = 0; index < count; index++) ring[index] = radarPoint(centerX, centerY, index, count, level);
      path(page, ring).append("h S\n");
    }
    for (int index = 0; index < count; index++) {
      float[] end = radarPoint(centerX, centerY, index, count, MAX_SCORE);
      page.append(fmt(centerX)).append(' ').append(fmt(centerY)).append(" m ")
          .append(fmt(end[0])).append(' ').append(fmt(end[1])).append(" l S\n");
    }
    float[][] shape = new float[count][];
    for (int index = 0; index < count; index++) {
      shape[index] = radarPoint(centerX, centerY, index, count, points.get(index).score());
    }
    page.append("q /GS1 gs ").append(PRIMARY_RGB).append(" rg ");
    path(page, shape).append("h f Q\n");
    page.append(PRIMARY_RGB).append(" RG 1.5 w ");
    path(page, shape).append("h S\n");
    page.append(PRIMARY_RGB).append(" rg\n");
    for (float[] point : shape) circle(page, point[0], point[1], 2.5f);
    page.append("Q\n");

    Style style = Style.SMALL;
    for (int index = 0; index < count; index++) {
      double angle = angle(index, count);
      double cos = Math.cos(angle);
      double sin = Math.sin(angle); // schermrichting: negatief is boven
      float labelX = (float) (centerX + (RADAR_RADIUS + RADAR_LABEL_GAP) * cos);
      float labelY = (float) (centerY - (RADAR_RADIUS + RADAR_LABEL_GAP) * sin);
      List<String> wrapped = labelLines(points.get(index).label(), style);
      float block = (wrapped.size() - 1) * style.leading;
      float baseline = sin < -0.2 ? labelY + block + 2 : sin > 0.2 ? labelY - style.size : labelY + block / 2 - 3;
      for (String text : wrapped) {
        float width = width(text, style);
        float x = Math.abs(cos) < 0.2 ? labelX - width / 2 : cos > 0 ? labelX : labelX - width;
        page.append("BT /F1 ").append(fmt(style.size)).append(" Tf ").append(fmt(x)).append(' ').append(fmt(baseline))
            .append(" Td (").append(encode(text)).append(") Tj ET\n");
        baseline -= style.leading;
      }
    }
  }

  private static void drawBars(StringBuilder page, List<RadarPoint> points, float top) {
    Style style = Style.SMALL;
    float y = top;
    for (RadarPoint point : points) {
      page.append("BT /F1 ").append(fmt(style.size)).append(" Tf ").append(fmt(MARGIN)).append(' ')
          .append(fmt(y - 10)).append(" Td (").append(encode(point.label())).append(") Tj ET\n");
      float barY = y - 24;
      float filled = (float) (CONTENT_WIDTH * Math.max(0, Math.min(MAX_SCORE, point.score())) / MAX_SCORE);
      page.append("q ").append(BORDER_RGB).append(" rg ").append(fmt(MARGIN)).append(' ').append(fmt(barY)).append(' ')
          .append(fmt(CONTENT_WIDTH)).append(" 8 re f ").append(PRIMARY_RGB).append(" rg ").append(fmt(MARGIN))
          .append(' ').append(fmt(barY)).append(' ').append(fmt(filled)).append(" 8 re f Q\n");
      y -= BAR_ROW_HEIGHT;
    }
  }

  /** Label afgebroken op woordgrenzen; meer dan {@link #RADAR_LABEL_MAX_LINES} regels wordt ingekort met een beletselteken. */
  static List<String> labelLines(String label, Style style) {
    List<String> wrapped = wrap(label, style, RADAR_LABEL_WIDTH);
    if (wrapped.size() <= RADAR_LABEL_MAX_LINES) return wrapped;
    List<String> kept = new ArrayList<>(wrapped.subList(0, RADAR_LABEL_MAX_LINES));
    String last = kept.get(RADAR_LABEL_MAX_LINES - 1);
    while (!last.isEmpty() && width(last + "…", style) > RADAR_LABEL_WIDTH) last = last.substring(0, last.length() - 1);
    kept.set(RADAR_LABEL_MAX_LINES - 1, last.stripTrailing() + "…");
    return kept;
  }

  /** Hoek per as, met de eerste as recht omhoog (zoals in de webapplicatie). */
  private static double angle(int index, int count) {
    return -Math.PI / 2 + 2 * Math.PI * index / count;
  }

  /** Punt op een as in PDF-coördinaten (y omhoog). */
  private static float[] radarPoint(float centerX, float centerY, int index, int count, double value) {
    double r = RADAR_RADIUS * Math.max(0, Math.min(MAX_SCORE, value)) / MAX_SCORE;
    double angle = angle(index, count);
    return new float[] {(float) (centerX + r * Math.cos(angle)), (float) (centerY - r * Math.sin(angle))};
  }

  private static StringBuilder path(StringBuilder page, float[][] points) {
    for (int index = 0; index < points.length; index++) {
      page.append(fmt(points[index][0])).append(' ').append(fmt(points[index][1])).append(index == 0 ? " m " : " l ");
    }
    return page;
  }

  /** Cirkel benaderd met vier Bézier-curves. */
  private static void circle(StringBuilder page, float x, float y, float r) {
    float k = 0.5523f * r;
    page.append(fmt(x + r)).append(' ').append(fmt(y)).append(" m ")
        .append(fmt(x + r)).append(' ').append(fmt(y + k)).append(' ').append(fmt(x + k)).append(' ').append(fmt(y + r))
        .append(' ').append(fmt(x)).append(' ').append(fmt(y + r)).append(" c ")
        .append(fmt(x - k)).append(' ').append(fmt(y + r)).append(' ').append(fmt(x - r)).append(' ').append(fmt(y + k))
        .append(' ').append(fmt(x - r)).append(' ').append(fmt(y)).append(" c ")
        .append(fmt(x - r)).append(' ').append(fmt(y - k)).append(' ').append(fmt(x - k)).append(' ').append(fmt(y - r))
        .append(' ').append(fmt(x)).append(' ').append(fmt(y - r)).append(" c ")
        .append(fmt(x + k)).append(' ').append(fmt(y - r)).append(' ').append(fmt(x + r)).append(' ').append(fmt(y - k))
        .append(' ').append(fmt(x + r)).append(' ').append(fmt(y)).append(" c f\n");
  }

  /** Codeert tekst als WinAnsi en schrijft niet-ASCII-bytes als octale escapes (PDF blijft 7-bit). */
  static String encode(String text) {
    CharsetEncoder encoder = WIN_ANSI.newEncoder()
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .onMalformedInput(CodingErrorAction.REPLACE)
        .replaceWith(new byte[] {'?'});
    ByteBuffer bytes;
    try {
      bytes = encoder.encode(CharBuffer.wrap(text.replace('\t', ' ')));
    } catch (CharacterCodingException exception) {
      bytes = ByteBuffer.wrap(text.replaceAll("[^\\x20-\\x7E]", "?").getBytes(StandardCharsets.US_ASCII));
    }
    StringBuilder result = new StringBuilder();
    while (bytes.hasRemaining()) {
      int value = bytes.get() & 0xFF;
      if (value == '\\' || value == '(' || value == ')') {
        result.append('\\').append((char) value);
      } else if (value < 0x20 || value > 0x7E) {
        result.append('\\').append(String.format(Locale.ROOT, "%03o", value));
      } else {
        result.append((char) value);
      }
    }
    return result.toString();
  }

  private static String fmt(float value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
