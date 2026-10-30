package com.example.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
  private Hashing() {}

  public static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is niet beschikbaar", exception);
    }
  }

  public static boolean constantTimeEquals(String left, String right) {
    return left != null && right != null && MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  /** Vingerafdruk van verzoekvelden voor idempotentiecontrole. */
  public static String fingerprint(Object... parts) {
    StringBuilder builder = new StringBuilder();
    for (Object part : parts) {
      builder.append(part == null ? "" : part.toString()).append('\u001f');
    }
    return sha256(builder.toString());
  }

  public static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(400, "Ongeldig verzoek", "De header X-Idempotency-Key is verplicht.",
          java.util.List.of(new Problem.FieldError("X-Idempotency-Key", "is verplicht")));
    }
    if (key.length() > 255) {
      throw new ApiException(400, "Ongeldig verzoek", "De header X-Idempotency-Key is te lang.",
          java.util.List.of(new Problem.FieldError("X-Idempotency-Key", "mag maximaal 255 tekens bevatten")));
    }
    return sha256(key.trim());
  }
}
