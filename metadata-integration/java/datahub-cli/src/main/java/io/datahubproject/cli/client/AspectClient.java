package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads aspects and aspect metadata through the GMS OpenAPI endpoints. */
public class AspectClient {

  private static final String ENTITY_PATH = "/openapi/v3/entity";
  private static final String REGISTRY_PATH = "/openapi/v1/registry/models";

  private final DataHubHttpClient http;

  public AspectClient(DataHubHttpClient http) {
    this.http = http;
  }

  /** An aspect as returned by OpenAPI v3: the payload plus its optional envelope fields. */
  public record AspectPayload(JsonNode value, JsonNode systemMetadata) {}

  /**
   * Fetches one aspect of one entity.
   *
   * @param version 0 for the latest version
   */
  public AspectPayload getAspect(
      String urn, String aspectName, long version, boolean withSystemMetadata) throws IOException {
    String path =
        ENTITY_PATH
            + "/"
            + DataHubHttpClient.encodePathSegment(Urns.entityType(urn))
            + "/"
            + DataHubHttpClient.encodePathSegment(urn)
            + "/"
            + DataHubHttpClient.encodePathSegment(aspectName);

    Map<String, String> query = new LinkedHashMap<>();
    if (version != 0) {
      query.put("version", Long.toString(version));
    }
    if (withSystemMetadata) {
      query.put("systemMetadata", "true");
    }

    JsonNode response = http.getJson(DataHubHttpClient.withQuery(path, query));
    return new AspectPayload(response.path("value"), response.get("systemMetadata"));
  }

  /** Entities per batchGet call; GMS reads them in one shot, but the URL-free body still grows. */
  private static final int BATCH_SIZE = 100;

  /**
   * Reads the same aspects for many entities, one request per entity type per batch, instead of one
   * request per entity.
   *
   * @return urn -> aspect name -> aspect value, with absent aspects simply missing
   */
  public Map<String, Map<String, JsonNode>> getAspectsBatch(
      List<String> urns, List<String> aspectNames) throws IOException {
    Map<String, Map<String, JsonNode>> byUrn = new LinkedHashMap<>();

    // batchGet is routed per entity type, so group first and keep the caller's order within a type.
    Map<String, List<String>> urnsByType = new LinkedHashMap<>();
    for (String urn : urns) {
      urnsByType.computeIfAbsent(Urns.entityType(urn), type -> new ArrayList<>()).add(urn);
    }

    for (Map.Entry<String, List<String>> entry : urnsByType.entrySet()) {
      List<String> typeUrns = entry.getValue();
      for (int start = 0; start < typeUrns.size(); start += BATCH_SIZE) {
        List<String> batch = typeUrns.subList(start, Math.min(start + BATCH_SIZE, typeUrns.size()));
        byUrn.putAll(fetchBatch(entry.getKey(), batch, aspectNames));
      }
    }
    return byUrn;
  }

  private Map<String, Map<String, JsonNode>> fetchBatch(
      String entityType, List<String> urns, List<String> aspectNames) throws IOException {
    // Body shape: [{"urn": "...", "<aspectName>": {}}, ...]
    List<Map<String, Object>> body = new ArrayList<>();
    for (String urn : urns) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("urn", urn);
      aspectNames.forEach(aspect -> item.put(aspect, Map.of()));
      body.add(item);
    }

    JsonNode response =
        http.postJson(
            ENTITY_PATH + "/" + DataHubHttpClient.encodePathSegment(entityType) + "/batchGet",
            body);

    Map<String, Map<String, JsonNode>> byUrn = new LinkedHashMap<>();
    response.forEach(
        entity -> {
          Map<String, JsonNode> aspects = new LinkedHashMap<>();
          for (String aspect : aspectNames) {
            JsonNode node = entity.get(aspect);
            if (node != null && !node.isNull()) {
              // Each aspect arrives wrapped as {"value": ..., "systemMetadata": ...}.
              aspects.put(aspect, node.path("value"));
            }
          }
          byUrn.put(entity.path("urn").asText(""), aspects);
        });
    return byUrn;
  }

  /**
   * Lists the aspect names an entity type declares, according to the server's own entity registry.
   *
   * <p>Returns null when the registry is not readable — the endpoint requires
   * MANAGE_SYSTEM_OPERATIONS_PRIVILEGE, which an ordinary token does not have. Callers treat that
   * as "cannot validate" rather than as an error, since the aspect fetch itself needs no such
   * privilege.
   */
  public List<String> listAspectNames(String entityType) throws IOException {
    JsonNode response =
        http.getJsonOptional(
            REGISTRY_PATH
                + "/entity/specifications/"
                + DataHubHttpClient.encodePathSegment(entityType)
                + "/aspects");
    if (response == null) {
      return null;
    }
    List<String> names = new ArrayList<>();
    response.fieldNames().forEachRemaining(names::add);
    names.sort(String::compareTo);
    return names;
  }
}
