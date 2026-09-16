package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** A DataHub policy, flattened to what the CLI renders. */
public record Policy(
    String urn,
    String name,
    String type,
    String state,
    String description,
    List<String> privileges,
    Actors actors) {

  /**
   * Who a policy applies to. {@code allUsers}, {@code allGroups} and {@code resourceOwners} are
   * dynamic rules that cannot be expanded into a list of principals client-side.
   */
  public record Actors(
      List<String> users,
      List<String> groups,
      List<String> roles,
      boolean allUsers,
      boolean allGroups,
      boolean resourceOwners) {

    /** Single-line rendering for the table view. */
    public String summarize() {
      StringJoiner parts = new StringJoiner(", ");
      if (allUsers) {
        parts.add("<allUsers>");
      }
      if (allGroups) {
        parts.add("<allGroups>");
      }
      if (resourceOwners) {
        parts.add("<resourceOwners>");
      }
      if (!users.isEmpty()) {
        parts.add("users: " + users.size());
      }
      if (!groups.isEmpty()) {
        parts.add("groups: " + groups.size());
      }
      if (!roles.isEmpty()) {
        parts.add("roles: " + roles.size());
      }
      return parts.length() == 0 ? "-" : parts.toString();
    }
  }

  static Policy fromJson(JsonNode node) {
    JsonNode actorsNode = node.path("actors");
    Actors actors =
        new Actors(
            stringList(actorsNode.path("users")),
            stringList(actorsNode.path("groups")),
            stringList(actorsNode.path("roles")),
            actorsNode.path("allUsers").asBoolean(false),
            actorsNode.path("allGroups").asBoolean(false),
            actorsNode.path("resourceOwners").asBoolean(false));

    return new Policy(
        node.path("urn").asText(""),
        node.path("name").asText(""),
        node.path("type").asText(""),
        node.path("state").asText(""),
        node.path("description").asText(""),
        stringList(node.path("privileges")),
        actors);
  }

  private static List<String> stringList(JsonNode arrayNode) {
    List<String> values = new ArrayList<>();
    if (arrayNode.isArray()) {
      arrayNode.forEach(element -> values.add(element.asText()));
    }
    return List.copyOf(values);
  }
}
