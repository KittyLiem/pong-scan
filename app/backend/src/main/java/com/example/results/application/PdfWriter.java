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
 * enz. correct worden weergegeven), automatische regelafbreking en paginering met paginanummers.
 */
public final class PdfWriter {
  private static final Charset WIN_ANSI = Charset.forName("windows-1252");
  private static final float PAGE_WIDTH = 595;
  private static final float PAGE_HEIGHT = 842;
  private static final float MARGIN = 50;
  private static final float BOTTOM = 60;

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

  private record Line(Style style, String text, float indent) {}

  private final List<Line> lines = new ArrayList<>();

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
    for (Line line : lines) {
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
      objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
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
