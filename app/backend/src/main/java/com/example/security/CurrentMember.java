package com.example.security;

import com.example.common.ApiException;
import jakarta.enterprise.context.RequestScoped;
import java.util.UUID;

/** Het aangemelde lid van het lopende verzoek, gevuld door {@link AuthenticationFilter}. */
@RequestScoped
public class CurrentMember {
  private UUID id;
  private UUID sessionId;
  private String role;
  private String accountIdentifier;

  void set(UUID id, UUID sessionId, String role, String accountIdentifier) {
    this.id = id;
    this.sessionId = sessionId;
    this.role = role;
    this.accountIdentifier = accountIdentifier;
  }

  public boolean isAuthenticated() {
    return id != null;
  }

  public UUID id() {
    if (id == null) throw ApiException.unauthorized("Aanmelden is vereist.");
    return id;
  }

  /** Lid-id of null (voor audit bij publieke endpoints). */
  public UUID idOrNull() {
    return id;
  }

  public UUID sessionId() {
    return sessionId;
  }

  public String role() {
    return role;
  }

  public String accountIdentifier() {
    return accountIdentifier;
  }

  public boolean isAdmin() {
    return "admin".equals(role);
  }
}
