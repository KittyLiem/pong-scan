package com.example.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    String type, String title, int status, String detail, String instance, List<FieldError> errors) {

  public static final String MEDIA_TYPE = "application/problem+json";

  public record FieldError(String field, String message) {}
}
