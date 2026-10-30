package com.example.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Basis security-headers vanuit de backend (nginx zet daarnaast o.a. de CSP). */
@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {
  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    var headers = response.getHeaders();
    headers.putSingle("X-Content-Type-Options", "nosniff");
    headers.putSingle("X-Frame-Options", "DENY");
    headers.putSingle("Referrer-Policy", "no-referrer");
    String path = request.getUriInfo().getPath();
    if (path != null && (path.startsWith("/v1") || path.startsWith("v1"))) {
      headers.putSingle("Cache-Control", "no-store");
      headers.putSingle("Pragma", "no-cache");
    }
  }
}
