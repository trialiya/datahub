package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Minimal GraphQL client over the GMS {@code /api/graphql} endpoint. */
public class GraphQLClient {

  private static final String GRAPHQL_PATH = "/api/graphql";

  private final DataHubHttpClient http;

  public GraphQLClient(DataHubHttpClient http) {
    this.http = http;
  }

  /**
   * Executes a query and returns the {@code data} node.
   *
   * @throws DataHubHttpClient.ApiException on a transport failure or a GraphQL error
   */
  public JsonNode query(String query, Map<String, Object> variables) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.put("variables", variables);

    JsonNode root = http.postJson(GRAPHQL_PATH, body);

    JsonNode errors = root.get("errors");
    if (errors != null && errors.isArray() && !errors.isEmpty()) {
      StringJoiner messages = new StringJoiner("; ");
      errors.forEach(error -> messages.add(error.path("message").asText()));
      throw new DataHubHttpClient.ApiException("GraphQL error: " + messages);
    }

    JsonNode data = root.get("data");
    if (data == null || data.isNull()) {
      throw new DataHubHttpClient.ApiException("GraphQL response contained no data");
    }
    return data;
  }
}
