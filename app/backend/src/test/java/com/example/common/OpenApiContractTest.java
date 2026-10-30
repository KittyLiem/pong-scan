package com.example.common;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OpenApiContractTest {
  private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  @Test
  void runtimePathsMethodsAndOperationIdsMatchLockedContract() throws Exception {
    String json = given().when().get("/q/openapi?format=json").then().statusCode(200)
        .contentType("application/json").extract().asString();
    JsonNode runtime = new ObjectMapper().readTree(json);
    JsonNode locked = new ObjectMapper(new YAMLFactory())
        .readTree(Path.of("../../locks/contracts/openapi.yaml").toFile());
    Map<String, Map<String, String>> expected = operations(locked);
    Map<String, Map<String, String>> actual = operations(runtime);
    assertThat(expected).as("Het contract bevat API-paden").isNotEmpty();
    List<String> differences = new ArrayList<>();
    Set<String> paths = new TreeSet<>(expected.keySet());
    paths.addAll(actual.keySet());
    for (String path : paths) {
      if (!actual.containsKey(path)) differences.add("Ontbrekend runtime-pad: " + path);
      else if (!expected.containsKey(path)) differences.add("Extra runtime-pad: " + path);
      else {
        Map<String, String> expectedMethods = expected.get(path);
        Map<String, String> actualMethods = actual.get(path);
        Set<String> methods = new TreeSet<>(expectedMethods.keySet());
        methods.addAll(actualMethods.keySet());
        for (String method : methods) {
          String operation = method.toUpperCase(java.util.Locale.ROOT) + " " + path;
          if (!actualMethods.containsKey(method)) differences.add("Ontbrekende runtime-methode: " + operation);
          else if (!expectedMethods.containsKey(method)) differences.add("Extra runtime-methode: " + operation);
          else if (!Objects.equals(expectedMethods.get(method), actualMethods.get(method))) {
            differences.add(operation + ": operationId contract=" + expectedMethods.get(method)
                + ", runtime=" + actualMethods.get(method));
          }
        }
      }
    }
    assertThat(differences).withFailMessage("OpenAPI wijkt af van het contract:%n%s", String.join("\n", differences)).isEmpty();
  }

  private static Map<String, Map<String, String>> operations(JsonNode document) {
    Map<String, Map<String, String>> paths = new TreeMap<>();
    document.path("paths").properties().forEach(path -> {
      Map<String, String> methods = new TreeMap<>();
      path.getValue().properties().forEach(operation -> {
        if (HTTP_METHODS.contains(operation.getKey())) {
          methods.put(operation.getKey(), operation.getValue().path("operationId").asText(null));
        }
      });
      paths.put(path.getKey(), methods);
    });
    return paths;
  }
}
