package com.example.common;

import jakarta.enterprise.context.RequestScoped;

/** Correlation-id van het lopende verzoek (ook in MDC als {@code correlationId}). */
@RequestScoped
public class RequestCorrelation {
  private String id;

  public String id() {
    return id;
  }

  public void set(String id) {
    this.id = id;
  }
}
