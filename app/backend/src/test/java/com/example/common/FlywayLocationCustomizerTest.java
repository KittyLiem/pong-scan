package com.example.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;

class FlywayLocationCustomizerTest {
  @Test
  void demoRequiresExplicitLocationAndLocalMode() {
    for (String location : List.of("db", "classpath:db", "db/../db/demo", "db/migration")) {
      for (boolean localMode : List.of(true, false)) {
        assertThat(new FlywayLocationCustomizer(List.of(location), localMode)
            .isAllowed(resource("db/demo/V1000__demo_data.sql"))).isFalse();
      }
    }
    assertThat(new FlywayLocationCustomizer(List.of("db/demo"), false)
        .isAllowed(resource("db/demo/V1000__demo_data.sql"))).isFalse();
    assertThat(new FlywayLocationCustomizer(List.of("classpath:db/demo"), true)
        .isAllowed(resource("db/demo/V1000__demo_data.sql"))).isTrue();
    assertThat(new FlywayLocationCustomizer(List.of("db/migration"), false)
        .isAllowed(resource("db/migration/../demo/V1000__demo_data.sql"))).isFalse();
    assertThat(new FlywayLocationCustomizer(List.of("db/migration"), false)
        .isAllowed(resource("db/migration/V1__schema.sql"))).isTrue();
  }

  private static LoadableResource resource(String path) {
    return new LoadableResource() {
      @Override public Reader read() { return new StringReader(""); }
      @Override public String getAbsolutePath() { return path; }
      @Override public String getAbsolutePathOnDisk() { return path; }
      @Override public String getFilename() { return path.substring(path.lastIndexOf('/') + 1); }
      @Override public String getRelativePath() { return path; }
    };
  }
}
