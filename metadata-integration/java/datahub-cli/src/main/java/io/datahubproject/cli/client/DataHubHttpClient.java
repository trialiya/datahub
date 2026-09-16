package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datahubproject.cli.config.CliConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP plumbing for the GMS APIs: auth header, error mapping, JSON parsing. */
public class DataHubHttpClient implements AutoCloseable {

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient;
  private final CliConfig config;
  private final Duration timeout;

  public DataHubHttpClient(CliConfig config, Duration timeout) {
    this.config = config;
    this.timeout = timeout;
    this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public String gmsUrl() {
    return config.gmsUrl();
  }

  public JsonNode postJson(String path, Object body) throws IOException {
    return send(
        request(path).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
  }

  public JsonNode getJson(String path) throws IOException {
    return send(request(path).GET());
  }

  /**
   * Like {@link #getJson}, but returns null on 404 and on the 403 that the caller is expected to
   * tolerate, so optional lookups do not have to catch exceptions for ordinary outcomes.
   */
  public JsonNode getJsonOptional(String path) throws IOException {
    try {
      return getJson(path);
    } catch (NotFoundException | ForbiddenException e) {
      return null;
    }
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(config.gmsUrl() + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
    config.tokenOptional().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
    return builder;
  }

  private JsonNode send(HttpRequest.Builder builder) throws IOException {
    HttpResponse<String> response;
    try {
      response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException("Request to " + config.gmsUrl() + " was interrupted", e);
    } catch (IOException e) {
      // Connect/timeout failures often carry a null or bare message, so name the target explicitly.
      throw new ApiException("Could not reach GMS at " + config.gmsUrl() + ": " + describe(e), e);
    }

    int status = response.statusCode();
    if (status == 401) {
      throw new ApiException(
          "Authentication failed (HTTP 401). Set a valid token via --token, $"
              + CliConfig.GMS_TOKEN_ENV
              + " or the config file.");
    }
    if (status == 403) {
      throw new ForbiddenException("Not authorized (HTTP 403): " + truncate(response.body()));
    }
    if (status == 404) {
      throw new NotFoundException("Not found (HTTP 404)");
    }
    if (status / 100 != 2) {
      throw new ApiException("GMS returned HTTP " + status + ": " + truncate(response.body()));
    }
    return mapper.readTree(response.body());
  }

  /**
   * Percent-encodes the characters that are illegal in a path segment, leaving URN syntax intact.
   */
  public static String encodePathSegment(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      // GMS matches URNs in the path literally, so ':', '(', ')' and ',' must survive as-is.
      if (Character.isLetterOrDigit(c) || "-._~:()/,".indexOf(c) >= 0) {
        encoded.append(c);
      } else {
        encoded.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return encoded.toString();
  }

  private static String describe(IOException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  static String truncate(String body) {
    return body.length() <= 500 ? body : body.substring(0, 500) + "…";
  }

  @Override
  public void close() {
    httpClient.close();
  }

  /** A transport-level or API-level failure, carrying a message meant for the user. */
  public static class ApiException extends IOException {
    public ApiException(String message) {
      super(message);
    }

    public ApiException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
      super(message);
    }
  }

  public static class NotFoundException extends ApiException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /** Convenience for callers that only need a map of query parameters appended to a path. */
  public static String withQuery(String path, Map<String, String> params) {
    if (params.isEmpty()) {
      return path;
    }
    StringBuilder builder = new StringBuilder(path).append('?');
    params.forEach((key, value) -> builder.append(key).append('=').append(value).append('&'));
    builder.setLength(builder.length() - 1);
    return builder.toString();
  }
}
