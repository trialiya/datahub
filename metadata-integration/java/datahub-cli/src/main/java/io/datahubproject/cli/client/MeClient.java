package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Fetches the caller's identity and platform privileges via the GraphQL {@code me} query. */
public class MeClient {

  private static final String CORP_USER_SELECTION =
      """
      corpUser {
        urn
        username
        isNativeUser
        properties { displayName email title active }
        status { active }
      }
      """;

  /**
   * The authenticated caller.
   *
   * @param platformPrivileges the UI-level privilege flags, or null when they could not be read
   */
  public record Me(
      String urn,
      String username,
      String displayName,
      String email,
      String title,
      Boolean nativeUser,
      Map<String, Boolean> platformPrivileges) {}

  private final GraphQLClient graphQLClient;
  private final SchemaIntrospector introspector;

  public MeClient(GraphQLClient graphQLClient, SchemaIntrospector introspector) {
    this.graphQLClient = graphQLClient;
    this.introspector = introspector;
  }

  public Me me() throws IOException {
    // PlatformPrivileges is a flat record of booleans that grows release by release, so the field
    // list comes from the server rather than from this build. Without introspection the identity
    // half of the answer is still worth printing.
    String privilegeSelection = null;
    try {
      privilegeSelection = introspector.selectionSet("PlatformPrivileges", 0);
    } catch (SchemaIntrospector.UnavailableException e) {
      privilegeSelection = null;
    }

    String query =
        "query me { me { "
            + CORP_USER_SELECTION
            + (privilegeSelection == null ? "" : " platformPrivileges " + privilegeSelection)
            + " } }";

    JsonNode me = graphQLClient.query(query, Map.of()).path("me");
    if (me.isMissingNode() || me.isNull()) {
      throw new DataHubHttpClient.ApiException(
          "The server returned no identity for this token; it may be expired or malformed.");
    }

    JsonNode corpUser = me.path("corpUser");
    JsonNode properties = corpUser.path("properties");
    return new Me(
        corpUser.path("urn").asText(null),
        corpUser.path("username").asText(null),
        properties.path("displayName").asText(null),
        properties.path("email").asText(null),
        properties.path("title").asText(null),
        corpUser.path("isNativeUser").isBoolean()
            ? corpUser.path("isNativeUser").asBoolean()
            : null,
        privilegeSelection == null ? null : toFlags(me.path("platformPrivileges")));
  }

  private static Map<String, Boolean> toFlags(JsonNode privileges) {
    Map<String, Boolean> flags = new TreeMap<>();
    privileges
        .properties()
        .forEach(entry -> flags.put(entry.getKey(), entry.getValue().asBoolean()));
    return flags;
  }
}
