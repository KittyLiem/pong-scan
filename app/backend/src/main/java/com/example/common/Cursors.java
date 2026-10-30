package com.example.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque offset-cursors voor paginering. */
public final class Cursors {
  private Cursors() {}

  public static int decode(String cursor) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      int value = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
      if (value < 0) throw new NumberFormatException();
      return value;
    } catch (IllegalArgumentException exception) {
      throw new ApiException(400, "Ongeldige cursor", "De pagineringscursor is ongeldig.");
    }
  }

  public static String encode(int offset) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
  }

  public static int pageSize(Integer requested) {
    int size = requested == null ? 20 : requested;
    if (size < 1 || size > 100) {
      throw new ApiException(400, "Ongeldig verzoek", "pageSize moet tussen 1 en 100 liggen.",
          java.util.List.of(new Problem.FieldError("pageSize", "moet tussen 1 en 100 liggen")));
    }
    return size;
  }
}
