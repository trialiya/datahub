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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Minimal GraphQL client over the GMS {@code /api/graphql} endpoint. */
public class GraphQLClient implements AutoCloseable {

  private static final String GRAPHQL_PATH = "/api/graphql";

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient httpClient;
  private final CliConfig config;
  private final Duration timeout;

  public GraphQLClient(CliConfig config, Duration timeout) {
    this.config = config;
    this.timeout = timeout;
    this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /**
   * Executes a query and returns the {@code data} node.
   *
   * @throws GraphQLException when the server replies with a non-2xx status or a GraphQL error
   */
  public JsonNode query(String query, Map<String, Object> variables) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("variables", variables);

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(config.gmsUrl() + GRAPHQL_PATH))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    config.tokenOptional().ifPresent(token -> request.header("Authorization", "Bearer " + token));

    HttpResponse<String> response;
    try {
      response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GraphQLException("Request to " + config.gmsUrl() + " was interrupted", e);
    } catch (IOException e) {
      // Connect/timeout failures often carry a null or bare message, so name the target explicitly.
      throw new GraphQLException(
          "Could not reach GMS at " + config.gmsUrl() + ": " + describe(e), e);
    }

    if (response.statusCode() == 401 || response.statusCode() == 403) {
      throw new GraphQLException(
          "Authentication failed (HTTP "
              + response.statusCode()
              + "). Set a valid token via --token, "
              + CliConfig.GMS_TOKEN_ENV
              + " or the config file.");
    }
    if (response.statusCode() / 100 != 2) {
      throw new GraphQLException(
          "GMS returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
    }

    JsonNode root = mapper.readTree(response.body());
    JsonNode errors = root.get("errors");
    if (errors != null && errors.isArray() && !errors.isEmpty()) {
      StringJoiner messages = new StringJoiner("; ");
      errors.forEach(error -> messages.add(error.path("message").asText()));
      throw new GraphQLException("GraphQL error: " + messages);
    }

    JsonNode data = root.get("data");
    if (data == null || data.isNull()) {
      throw new GraphQLException(
          "GraphQL response contained no data: " + truncate(response.body()));
    }
    return data;
  }

  private static String describe(IOException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static String truncate(String body) {
    return body.length() <= 500 ? body : body.substring(0, 500) + "…";
  }

  @Override
  public void close() {
    httpClient.close();
  }

  /** Signals a transport-level or GraphQL-level failure; carries a message meant for the user. */
  public static class GraphQLException extends IOException {
    public GraphQLException(String message) {
      super(message);
    }

    public GraphQLException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
