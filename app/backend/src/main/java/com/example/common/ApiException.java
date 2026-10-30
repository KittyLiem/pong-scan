package com.example.common;

import java.util.List;

/** Fout die als application/problem+json wordt teruggegeven. Titels en details zijn Nederlandstalig. */
public class ApiException extends RuntimeException {
  private final int status;
  private final String title;
  private final List<Problem.FieldError> errors;
  private Long retryAfter;

  public ApiException(int status, String title, String detail) {
    this(status, title, detail, null);
  }

  public ApiException(int status, String title, String detail, List<Problem.FieldError> errors) {
    super(detail);
    this.status = status;
    this.title = title;
    this.errors = errors;
  }

  public ApiException retryAfter(long seconds) {
    this.retryAfter = seconds;
    return this;
  }

  public Long retryAfter() {
    return retryAfter;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  public List<Problem.FieldError> errors() {
    return errors;
  }

  public static ApiException badRequest(String detail) {
    return new ApiException(400, "Ongeldig verzoek", detail);
  }

  public static ApiException unauthorized(String detail) {
    return new ApiException(401, "Niet aangemeld", detail);
  }

  public static ApiException forbidden(String detail) {
    return new ApiException(403, "Geen toegang", detail);
  }

  public static ApiException notFound(String detail) {
    return new ApiException(404, "Niet gevonden", detail);
  }

  public static ApiException conflict(String title, String detail) {
    return new ApiException(409, title, detail);
  }

  public static ApiException versionConflict() {
    return new ApiException(409, "Versieconflict",
        "De gegevens zijn intussen gewijzigd. Laad ze opnieuw en probeer het nog eens.");
  }

  public static ApiException idempotencyConflict() {
    return new ApiException(409, "Idempotentieconflict",
        "De X-Idempotency-Key is al gebruikt voor een ander verzoek.");
  }

  public static ApiException unprocessable(String title, String detail) {
    return new ApiException(422, title, detail);
  }
}
