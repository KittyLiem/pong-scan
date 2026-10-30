package com.example.security;

import com.example.common.ApiException;
import jakarta.annotation.Priority;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;

/**
 * Deny-by-default: iedere JAX-RS resource-methode vereist een geldig bearer-token, behalve methoden (of klassen)
 * met {@link PermitAll}. Methoden/klassen met {@link AdminOnly} vereisen daarnaast de rol {@code admin}.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {
  @Context ResourceInfo resourceInfo;
  @Inject TokenService tokens;
  @Inject CurrentMember currentMember;

  @Override
  public void filter(ContainerRequestContext request) {
    Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
    Class<?> resourceClass = resourceInfo == null ? null : resourceInfo.getResourceClass();
    if (isPublic(method, resourceClass)) {
      return;
    }
    TokenService.Verified verified = tokens.verify(request.getHeaderString("Authorization"));
    currentMember.set(verified.memberId(), verified.sessionId(), verified.role(), verified.accountIdentifier());
    if (requiresAdmin(method, resourceClass) && !currentMember.isAdmin()) {
      throw ApiException.forbidden("Deze functie is alleen beschikbaar voor beheerders.");
    }
  }

  static boolean isPublic(Method method, Class<?> resourceClass) {
    if (method != null && method.isAnnotationPresent(PermitAll.class)) return true;
    return method != null && resourceClass != null && resourceClass.isAnnotationPresent(PermitAll.class);
  }

  static boolean requiresAdmin(Method method, Class<?> resourceClass) {
    return (method != null && method.isAnnotationPresent(AdminOnly.class))
        || (resourceClass != null && resourceClass.isAnnotationPresent(AdminOnly.class))
        || (method != null && method.getDeclaringClass().isAnnotationPresent(AdminOnly.class));
  }
}
