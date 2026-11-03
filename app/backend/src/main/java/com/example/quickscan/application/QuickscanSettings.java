package com.example.quickscan.application;

import com.example.common.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Instellingen van de quickscan; staat uit op tenant-installaties. */
@ApplicationScoped
public class QuickscanSettings {
  @ConfigProperty(name = "app.quickscan.enabled", defaultValue = "true")
  boolean enabled;
  @ConfigProperty(name = "app.quickscan.max-per-account", defaultValue = "1")
  int maxPerAccount;

  public boolean enabled() {
    return enabled;
  }

  public int maxPerAccount() {
    return maxPerAccount;
  }

  /** 404 als de quickscan op deze installatie niet bestaat. */
  public void requireEnabled() {
    if (!enabled) {
      throw ApiException.notFound("Quickscan is niet beschikbaar.");
    }
  }
}
