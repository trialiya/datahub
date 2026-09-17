package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;

/**
 * Reads what an instance says about itself: the deployment facts from the GMS {@code /config}
 * servlet and the feature configuration the UI runs on from GraphQL {@code appConfig}.
 */
public class ServerConfigClient {

  private static final String CONFIG_PATH = "/config";

  /**
   * How far to descend into AppConfig. Its sections are one level down and a few of them (the
   * visual config in particular) nest one level further; below that there is nothing but the
   * privilege catalogues, which are lists of objects and are skipped anyway.
   */
  private static final int APP_CONFIG_DEPTH = 2;

  private final DataHubHttpClient http;
  private final GraphQLClient graphQLClient;
  private final SchemaIntrospector introspector;

  public ServerConfigClient(
      DataHubHttpClient http, GraphQLClient graphQLClient, SchemaIntrospector introspector) {
    this.http = http;
    this.graphQLClient = graphQLClient;
    this.introspector = introspector;
  }

  /**
   * Returns {@code {"server": ..., "appConfig": ...}}.
   *
   * <p>Either half may be missing: {@code /config} is not served by every deployment, and {@code
   * appConfig} needs introspection to build its selection set.
   */
  public ObjectNode fetch() throws IOException {
    ObjectNode combined = http.mapper().createObjectNode();

    JsonNode server = http.getJsonOptional(CONFIG_PATH);
    if (server != null) {
      combined.set("server", server);
    }

    try {
      String selection = introspector.selectionSet("AppConfig", APP_CONFIG_DEPTH);
      JsonNode appConfig =
          graphQLClient
              .query("query appConfig { appConfig " + selection + " }", Map.of())
              .path("appConfig");
      if (!appConfig.isMissingNode() && !appConfig.isNull()) {
        combined.set("appConfig", appConfig);
      }
    } catch (SchemaIntrospector.UnavailableException e) {
      combined.put("appConfigError", e.getMessage());
    }

    if (!combined.has("server") && !combined.has("appConfig")) {
      throw new DataHubHttpClient.ApiException(
          "Neither " + CONFIG_PATH + " nor appConfig could be read from " + http.gmsUrl());
    }
    return combined;
  }
}
