package com.example.common;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.jboss.logging.Logger;

/**
 * {@code quarkus.flyway.locations} is een build-time-instelling. Beide locaties ({@code db/migration} en
 * {@code db/demo}) worden daarom altijd meegebouwd en deze customizer bepaalt op runtime (via
 * {@code app.flyway.locations}) welke migraties daadwerkelijk worden toegepast.
 */
@Singleton
public class FlywayLocationCustomizer implements FlywayConfigurationCustomizer {
  private static final Logger LOG = Logger.getLogger(FlywayLocationCustomizer.class);

  private final List<String> allowed;
  private final boolean demoAllowed;

  public FlywayLocationCustomizer(@ConfigProperty(name = "app.flyway.locations") List<String> locations,
      @ConfigProperty(name = "app.allow-insecure-local-defaults", defaultValue = "false") boolean localMode) {
    this.demoAllowed = localMode && locations.stream().map(String::trim)
        .anyMatch(value -> value.equals("db/demo") || value.equals("classpath:db/demo"));
    this.allowed = locations.stream().map(FlywayLocationCustomizer::normalize).filter(value -> !value.isEmpty())
        .toList();
  }

  @Override
  public void customize(FluentConfiguration configuration) {
    com.example.security.ProductionConfigCheck.verifyProduction();
    LOG.infof("Flyway-locaties actief: %s", allowed);
    ResourceProvider delegate = configuration.getResourceProvider();
    if (delegate == null) {
      // Scan alleen de concrete ingebouwde locaties, nooit een recursieve ouderlocatie.
      List<String> concrete = new ArrayList<>();
      if (isAllowedPath("db/migration/V1__schema.sql")) concrete.add("db/migration");
      if (isAllowedPath("db/demo/V1000__demo_data.sql")) concrete.add("db/demo");
      configuration.locations(concrete.toArray(String[]::new));
      return;
    }
    configuration.resourceProvider(new ResourceProvider() {
      @Override
      public LoadableResource getResource(String name) {
        LoadableResource resource = delegate.getResource(name);
        return resource != null && isAllowed(resource) ? resource : null;
      }

      @Override
      public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return delegate.getResources(prefix, suffixes).stream().filter(FlywayLocationCustomizer.this::isAllowed)
            .toList();
      }
    });
  }

  boolean isAllowed(LoadableResource resource) {
    return isAllowedPath(resource.getAbsolutePath());
  }

  private boolean isAllowedPath(String value) {
    String path = normalize(value);
    if ((path.startsWith("db/demo/") || path.contains("/db/demo/")) && !demoAllowed) return false;
    return allowed.stream().anyMatch(location -> path.startsWith(location + "/"));
  }

  static String normalize(String value) {
    String result = value == null ? "" : value.trim().replace('\\', '/');
    if (result.startsWith("classpath:")) result = result.substring("classpath:".length());
    while (result.startsWith("/")) result = result.substring(1);
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return Path.of(result).normalize().toString().replace('\\', '/');
  }

}
