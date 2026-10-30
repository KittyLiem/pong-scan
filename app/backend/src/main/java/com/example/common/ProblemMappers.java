package com.example.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Vertaalt alle fouten naar application/problem+json met Nederlandstalige titels, zonder stacktraces. */
public class ProblemMappers {
  private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

  public static Response problem(int status, String title, String detail, String instance,
      List<Problem.FieldError> errors) {
    Problem body = new Problem("about:blank", title, status, detail, instance,
        errors == null || errors.isEmpty() ? null : errors);
    return Response.status(status).type(Problem.MEDIA_TYPE).entity(body).build();
  }

  private static String instance(UriInfo uriInfo) {
    try {
      return uriInfo == null ? null : uriInfo.getRequestUri().getRawPath();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  @ServerExceptionMapper
  public Response mapApi(ApiException exception, UriInfo uriInfo) {
    Response response = problem(exception.status(), exception.title(), exception.getMessage(), instance(uriInfo),
        exception.errors());
    return exception.retryAfter() == null ? response
        : Response.fromResponse(response).header("Retry-After", exception.retryAfter()).build();
  }

  @ServerExceptionMapper
  public Response mapConstraint(ConstraintViolationException exception, UriInfo uriInfo) {
    List<Problem.FieldError> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
      errors.add(new Problem.FieldError(fieldName(violation.getPropertyPath()), violation.getMessage()));
    }
    errors.sort(Comparator.comparing(Problem.FieldError::field).thenComparing(Problem.FieldError::message));
    return problem(400, "Ongeldige invoer", "Een of meer velden zijn ontbrekend of ongeldig.",
        instance(uriInfo), errors);
  }

  @ServerExceptionMapper
  public Response mapMismatched(MismatchedInputException exception, UriInfo uriInfo) {
    return jsonProblem(exception, uriInfo);
  }

  @ServerExceptionMapper
  public Response mapInvalidFormat(InvalidFormatException exception, UriInfo uriInfo) {
    return jsonProblem(exception, uriInfo);
  }

  @ServerExceptionMapper
  public Response mapJson(JsonProcessingException exception, UriInfo uriInfo) {
    return jsonProblem(exception, uriInfo);
  }

  private Response jsonProblem(JsonProcessingException exception, UriInfo uriInfo) {
    List<Problem.FieldError> errors = null;
    if (exception instanceof MismatchedInputException mismatched && !mismatched.getPath().isEmpty()) {
      StringBuilder field = new StringBuilder();
      for (var reference : mismatched.getPath()) {
        if (reference.getFieldName() != null) {
          if (!field.isEmpty()) field.append('.');
          field.append(reference.getFieldName());
        } else if (reference.getIndex() >= 0) {
          field.append('[').append(reference.getIndex()).append(']');
        }
      }
      errors = List.of(new Problem.FieldError(field.toString(), "heeft een ongeldig type of formaat"));
    }
    return problem(400, "Ongeldige invoer", "De request-body is geen geldige JSON voor dit verzoek.",
        instance(uriInfo), errors);
  }

  @ServerExceptionMapper
  public Response mapWebApplication(WebApplicationException exception, UriInfo uriInfo) {
    int status = exception.getResponse() == null ? 500 : exception.getResponse().getStatus();
    if (status >= 500 && isOptimisticLockFailure(exception)) {
      return versionConflict(uriInfo);
    }
    if (status >= 500) {
      LOG.error("Onverwachte fout bij verwerken van verzoek", exception);
    }
    if (exception.getCause() instanceof JsonProcessingException json) {
      return jsonProblem(json, uriInfo);
    }
    return problem(status, titleFor(status), detailFor(status), instance(uriInfo), null);
  }

  @ServerExceptionMapper
  public Response mapUnexpected(Throwable exception, UriInfo uriInfo) {
    Throwable apiCause = findCause(exception, ApiException.class);
    if (apiCause instanceof ApiException api) {
      return mapApi(api, uriInfo);
    }
    if (isOptimisticLockFailure(exception)) {
      return versionConflict(uriInfo);
    }
    LOG.error("Onverwachte fout bij verwerken van verzoek", exception);
    return problem(500, "Interne fout", "Er is een onverwachte fout opgetreden. Probeer het later opnieuw.",
        instance(uriInfo), null);
  }

  /** Optimistic-lock-fouten (ook verpakt in RollbackException e.d.) worden 409 "Versieconflict". */
  @ServerExceptionMapper
  public Response mapOptimisticLock(jakarta.persistence.OptimisticLockException exception, UriInfo uriInfo) {
    return versionConflict(uriInfo);
  }

  @ServerExceptionMapper
  public Response mapStaleState(org.hibernate.StaleStateException exception, UriInfo uriInfo) {
    return versionConflict(uriInfo);
  }

  private static Response versionConflict(UriInfo uriInfo) {
    ApiException conflict = ApiException.versionConflict();
    return problem(409, conflict.title(), conflict.getMessage(), instance(uriInfo), null);
  }

  static boolean isOptimisticLockFailure(Throwable exception) {
    return findCause(exception, jakarta.persistence.OptimisticLockException.class) != null
        || findCause(exception, org.hibernate.StaleStateException.class) != null;
  }

  private static Throwable findCause(Throwable exception, Class<? extends Throwable> type) {
    Throwable current = exception;
    for (int depth = 0; current != null && depth < 20; depth++) {
      if (type.isInstance(current)) return current;
      if (current.getCause() == current) break;
      current = current.getCause();
    }
    return null;
  }

  static String fieldName(Path path) {
    // Voorbeelden: "createSession.input.desiredRoute", "updateScan.input.goals[0].name",
    // "saveAnswer.input.evidenceReferences[1].<list element>", "registerMember.idempotencyKey".
    String raw = path.toString().replace(".<list element>", "").replace("<list element>", "");
    int firstDot = raw.indexOf('.');
    String withoutMethod = firstDot < 0 ? raw : raw.substring(firstDot + 1);
    int paramDot = withoutMethod.indexOf('.');
    int paramBracket = withoutMethod.indexOf('[');
    String field;
    if (paramDot >= 0 && (paramBracket < 0 || paramDot < paramBracket)) {
      field = withoutMethod.substring(paramDot + 1);
    } else {
      field = withoutMethod;
    }
    return "idempotencyKey".equals(field) ? "X-Idempotency-Key" : field;
  }

  private static String titleFor(int status) {
    return switch (status) {
      case 400 -> "Ongeldig verzoek";
      case 401 -> "Niet aangemeld";
      case 403 -> "Geen toegang";
      case 404 -> "Niet gevonden";
      case 405 -> "Methode niet toegestaan";
      case 406 -> "Niet aanvaardbaar";
      case 409 -> "Conflict";
      case 413 -> "Verzoek te groot";
      case 415 -> "Mediatype niet ondersteund";
      case 422 -> "Niet verwerkbaar";
      default -> status >= 500 ? "Interne fout" : "Fout";
    };
  }

  private static String detailFor(int status) {
    return switch (status) {
      case 400 -> "Het verzoek is ongeldig.";
      case 401 -> "Aanmelden is vereist.";
      case 403 -> "U heeft geen toegang tot deze functie.";
      case 404 -> "De gevraagde resource bestaat niet.";
      case 405 -> "Deze HTTP-methode is niet toegestaan voor deze resource.";
      case 406 -> "Het gevraagde antwoordformaat wordt niet ondersteund.";
      case 413 -> "De request-body is groter dan toegestaan (maximaal 1 MB).";
      case 415 -> "Gebruik Content-Type application/json.";
      default -> status >= 500 ? "Er is een onverwachte fout opgetreden. Probeer het later opnieuw."
          : "Het verzoek kon niet worden verwerkt.";
    };
  }
}
