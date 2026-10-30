package com.example.common;

public final class Texts {
  private Texts() {}

  /** Trimt en zet lege tekst om naar null. */
  public static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
