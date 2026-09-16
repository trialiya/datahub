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
