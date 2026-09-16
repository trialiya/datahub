package io.datahubproject.cli.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

public class CliConfigTest {

  private static Path writeConfig(String contents) throws IOException {
    Path file = Files.createTempFile("datahub-cli", ".yaml");
    file.toFile().deleteOnExit();
    Files.writeString(file, contents);
    return file;
  }

  @Test
  public void testOptionsWinOverEnvAndFile() throws IOException {
    Path file = writeConfig("gmsUrl: http://from-file:8080\ntoken: file-token\n");
    Map<String, String> env =
        Map.of(
            CliConfig.GMS_URL_ENV, "http://from-env:8080",
            CliConfig.GMS_TOKEN_ENV, "env-token");

    CliConfig config = CliConfig.resolve("http://from-option:8080", "option-token", file, env);

    assertEquals(config.gmsUrl(), "http://from-option:8080");
    assertEquals(config.token(), "option-token");
  }

  @Test
  public void testEnvWinsOverFile() throws IOException {
    Path file = writeConfig("gmsUrl: http://from-file:8080\ntoken: file-token\n");
    Map<String, String> env = Map.of(CliConfig.GMS_URL_ENV, "http://from-env:8080");

    CliConfig config = CliConfig.resolve(null, null, file, env);

    assertEquals(config.gmsUrl(), "http://from-env:8080");
    // Only the URL was set in the environment, so the token still comes from the file.
    assertEquals(config.token(), "file-token");
  }

  @Test
  public void testDefaultsWhenNothingIsConfigured() {
    CliConfig config = CliConfig.resolve(null, null, null, Map.of());

    assertEquals(config.gmsUrl(), CliConfig.DEFAULT_GMS_URL);
    assertNull(config.token());
  }

  @Test
  public void testTrailingSlashIsStrippedSoPathsConcatenateCleanly() {
    CliConfig config = CliConfig.resolve("http://localhost:8080/", null, null, Map.of());

    assertEquals(config.gmsUrl(), "http://localhost:8080");
  }

  @Test
  public void testMissingExplicitConfigFileIsAnError() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CliConfig.resolve(null, null, Path.of("/nonexistent/datahub-cli.yaml"), Map.of()));
  }
}
