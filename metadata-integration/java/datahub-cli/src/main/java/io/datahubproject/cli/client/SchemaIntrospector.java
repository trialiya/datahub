package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds GraphQL selection sets from the server's own schema.
 *
 * <p>Config types such as {@code AppConfig} grow with every release -- the flag section alone
 * carries dozens of fields -- and a selection set written by hand would describe the server the CLI
 * was built against rather than the one it is talking to. Asking the server which fields exist
 * keeps the output honest across versions.
 *
 * <p>Introspection can be switched off ({@code GRAPHQL_QUERY_INTROSPECTION_ENABLED=false}), in
 * which case the query below returns a null type and callers get {@link UnavailableException}.
 */
public class SchemaIntrospector {

  /**
   * Three levels of {@code ofType} unwrap the deepest wrapper GraphQL puts around a named type,
   * {@code NON_NULL(LIST(NON_NULL(T)))}.
   */
  private static final String FIELDS_FRAGMENT =
      """
      {
        name
        fields {
          name
          args { name }
          type {
            kind name
            ofType { kind name ofType { kind name ofType { kind name } } }
          }
        }
      }
      """;

  /** Thrown when the server does not answer introspection queries. */
  public static class UnavailableException extends IOException {
    public UnavailableException(String message) {
      super(message);
    }
  }

  /**
   * A field of an object type, with its wrappers already stripped.
   *
   * @param list whether the named type was wrapped in a LIST
   */
  public record Field(
      String name, String typeName, String kind, boolean list, boolean takesArguments) {
    public boolean isLeaf() {
      return "SCALAR".equals(kind) || "ENUM".equals(kind);
    }

    /** A single nested object, i.e. one worth descending into. */
    public boolean isNestedObject() {
      return "OBJECT".equals(kind) && !list;
    }
  }

  private final GraphQLClient graphQLClient;
  private final Map<String, List<Field>> cache = new HashMap<>();
  private boolean describedAnything;

  public SchemaIntrospector(GraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  /**
   * Returns a selection set covering every leaf field of {@code typeName}, descending into nested
   * object fields up to {@code maxDepth} levels.
   *
   * <p>Lists of objects are skipped: they are catalogues rather than settings, and flattening them
   * into a key-value view would not read well.
   */
  public String selectionSet(String typeName, int maxDepth) throws IOException {
    // Each level is fetched in a single request, so the depth bounds the round-trips.
    Set<String> level = Set.of(typeName);
    for (int depth = 0; depth <= maxDepth && !level.isEmpty(); depth++) {
      fetch(level);
      Set<String> next = new LinkedHashSet<>();
      if (depth < maxDepth) {
        for (String type : level) {
          cache.getOrDefault(type, List.of()).stream()
              .filter(field -> field.isNestedObject() && !field.takesArguments())
              .forEach(field -> next.add(field.typeName()));
        }
      }
      next.removeAll(cache.keySet());
      level = next;
    }
    return render(typeName, maxDepth);
  }

  private String render(String typeName, int remainingDepth) {
    StringBuilder selection = new StringBuilder("{");
    for (Field field : cache.getOrDefault(typeName, List.of())) {
      if (field.takesArguments()) {
        continue;
      }
      if (field.isLeaf()) {
        selection.append(' ').append(field.name());
      } else if (field.isNestedObject()
          && remainingDepth > 0
          && cache.containsKey(field.typeName())) {
        String nested = render(field.typeName(), remainingDepth - 1);
        // An object whose own fields were all skipped would produce "{ }", which is a syntax error.
        if (!"{ }".equals(nested)) {
          selection.append(' ').append(field.name()).append(' ').append(nested);
        }
      }
    }
    return selection.append(" }").toString();
  }

  /** Fetches the fields of several types in one request, using an alias per type. */
  private void fetch(Collection<String> typeNames) throws IOException {
    List<String> wanted = typeNames.stream().filter(name -> !cache.containsKey(name)).toList();
    if (wanted.isEmpty()) {
      return;
    }

    StringBuilder query = new StringBuilder("query introspect {");
    for (int i = 0; i < wanted.size(); i++) {
      query
          .append(" t")
          .append(i)
          .append(": __type(name: \"")
          .append(wanted.get(i))
          .append("\") ")
          .append(FIELDS_FRAGMENT);
    }
    query.append("}");

    JsonNode data = graphQLClient.query(query.toString(), Map.of());
    int described = 0;
    for (int i = 0; i < wanted.size(); i++) {
      JsonNode type = data.path("t" + i);
      // A single null means the server does not know that type, which is survivable: the field
      // pointing at it is simply left out. All of them null means introspection is switched off.
      cache.put(
          wanted.get(i),
          type.isMissingNode() || type.isNull() ? List.of() : parseFields(type.path("fields")));
      if (!type.isMissingNode() && !type.isNull()) {
        described++;
        describedAnything = true;
      }
    }
    // Only the first request can tell the two cases apart: once the server has described
    // something, a batch of nulls just means those types are unknown to it.
    if (described == 0 && !describedAnything) {
      throw new UnavailableException(
          "The server described none of "
              + String.join(", ", wanted)
              + "; GraphQL introspection looks disabled on this instance.");
    }
  }

  private static List<Field> parseFields(JsonNode fields) {
    List<Field> parsed = new ArrayList<>();
    if (!fields.isArray()) {
      return parsed;
    }
    for (JsonNode field : fields) {
      JsonNode type = field.path("type");
      JsonNode named = unwrap(type);
      parsed.add(
          new Field(
              field.path("name").asText(),
              named.path("name").asText(),
              named.path("kind").asText(),
              isList(type),
              !field.path("args").isEmpty()));
    }
    return parsed;
  }

  private static boolean isList(JsonNode type) {
    JsonNode current = type;
    while (current.path("name").isNull()) {
      if ("LIST".equals(current.path("kind").asText())) {
        return true;
      }
      if (!current.hasNonNull("ofType")) {
        break;
      }
      current = current.path("ofType");
    }
    return false;
  }

  /** Walks past the NON_NULL and LIST wrappers down to the named type. */
  private static JsonNode unwrap(JsonNode type) {
    JsonNode current = type;
    while (current.path("name").isNull() && current.hasNonNull("ofType")) {
      current = current.path("ofType");
    }
    return current;
  }
}
