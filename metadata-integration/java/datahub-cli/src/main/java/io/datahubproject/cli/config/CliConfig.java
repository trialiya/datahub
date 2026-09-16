package io.datahubproject.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Connection settings for the CLI, resolved from (highest precedence first) command line options,
 * environment variables and a YAML config file.
 */
public record CliConfig(String gmsUrl, String token) {

  public static final String DEFAULT_GMS_URL = "http://localhost:8080";

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  /** Config file looked up next to the CLI, then in the user's home directory. */
  private static final String CONFIG_FILE_NAME = "datahub-cli.yaml";

  private static final String HOME_CONFIG_PATH = ".datahub/" + CONFIG_FILE_NAME;

  public static final String GMS_URL_ENV = "DATAHUB_GMS_URL";
  public static final String GMS_TOKEN_ENV = "DATAHUB_GMS_TOKEN";

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ConfigFile(String gmsUrl, String token) {}

  /**
   * Resolves the effective configuration.
   *
   * @param urlOption {@code --url}, or null when not passed
   * @param tokenOption {@code --token}, or null when not passed
   * @param configFileOption {@code --config}, or null to search the default locations
   * @param env environment lookup, injected so it can be exercised in tests
   */
  public static CliConfig resolve(
      String urlOption, String tokenOption, Path configFileOption, Map<String, String> env) {
    ConfigFile fromFile = readConfigFile(configFileOption);

    String url =
        firstNonBlank(
            urlOption,
            env.get(GMS_URL_ENV),
            fromFile == null ? null : fromFile.gmsUrl(),
            DEFAULT_GMS_URL);
    String token =
        firstNonBlank(
            tokenOption, env.get(GMS_TOKEN_ENV), fromFile == null ? null : fromFile.token());

    return new CliConfig(stripTrailingSlash(url), token);
  }

  private static ConfigFile readConfigFile(Path explicitPath) {
    Path path = explicitPath != null ? explicitPath : findDefaultConfigFile();
    if (path == null) {
      return null;
    }
    if (!Files.isReadable(path)) {
      // An explicitly requested file that is missing is a user error; a missing default is not.
      if (explicitPath != null) {
        throw new IllegalArgumentException("Config file not found or not readable: " + path);
      }
      return null;
    }
    try {
      return YAML_MAPPER.readValue(path.toFile(), ConfigFile.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to parse config file " + path, e);
    }
  }

  private static Path findDefaultConfigFile() {
    Path local = Path.of(CONFIG_FILE_NAME);
    if (Files.isReadable(local)) {
      return local;
    }
    Path home = Path.of(System.getProperty("user.home", ""), HOME_CONFIG_PATH);
    return Files.isReadable(home) ? home : null;
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate.trim();
      }
    }
    return null;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  public Optional<String> tokenOptional() {
    return Optional.ofNullable(token);
  }
}
