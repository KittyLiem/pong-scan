package com.example.common;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;

/**
 * Neemt een binnenkomende X-Correlation-Id alleen over als die veilig is (^[A-Za-z0-9-]{1,64}$), anders wordt
 * een nieuwe gegenereerd. De id staat in MDC, in {@link RequestCorrelation} en in iedere response.
 */
@Provider
@PreMatching
@Priority(1)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {
  public static final String HEADER = "X-Correlation-Id";
  public static final String PROPERTY = "correlationId";
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  @Inject RequestCorrelation correlation;

  @Override
  public void filter(ContainerRequestContext request) {
    String incoming = request.getHeaderString(HEADER);
    String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    request.setProperty(PROPERTY, id);
    correlation.set(id);
    MDC.put(PROPERTY, id);
  }

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    Object id = request.getProperty(PROPERTY);
    if (id == null) {
      id = UUID.randomUUID().toString();
    }
    response.getHeaders().putSingle(HEADER, id.toString());
  }

  public static boolean isSafe(String value) {
    return value != null && SAFE.matcher(value).matches();
  }
}
