package io.datahubproject.cli.client;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

public class SchemaIntrospectorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern ALIASED_TYPE =
      Pattern.compile("(t\\d+): __type\\(name: \"([^\"]+)\"\\)");

  /**
   * Answers introspection from a canned schema, so the walker can be exercised without a server.
   */
  private static final class FakeGraphQL extends GraphQLClient {

    private final Map<String, String> schema;
    private final List<List<String>> requestedTypes = new ArrayList<>();

    FakeGraphQL(Map<String, String> schema) {
      super(null);
      this.schema = schema;
    }

    @Override
    public JsonNode query(String query, Map<String, Object> variables) throws IOException {
      ObjectNode data = MAPPER.createObjectNode();
      List<String> requested = new ArrayList<>();
      Matcher matcher = ALIASED_TYPE.matcher(query);
      while (matcher.find()) {
        String alias = matcher.group(1);
        String typeName = matcher.group(2);
        requested.add(typeName);
        String fields = schema.get(typeName);
        if (fields == null) {
          data.putNull(alias);
        } else {
          ObjectNode type = data.putObject(alias);
          type.put("name", typeName);
          type.set("fields", MAPPER.readTree(fields));
        }
      }
      requestedTypes.add(requested);
      return data;
    }
  }

  private static String scalar(String name) {
    return """
        {"name": "%s", "args": [],
         "type": {"kind": "NON_NULL", "name": null,
                  "ofType": {"kind": "SCALAR", "name": "Boolean", "ofType": null}}}
        """
        .formatted(name);
  }

  private static String object(String name, String typeName) {
    return """
        {"name": "%s", "args": [],
         "type": {"kind": "OBJECT", "name": "%s", "ofType": null}}
        """
        .formatted(name, typeName);
  }

  private static String objectList(String name, String typeName) {
    return """
        {"name": "%s", "args": [],
         "type": {"kind": "NON_NULL", "name": null,
                  "ofType": {"kind": "LIST", "name": null,
                             "ofType": {"kind": "OBJECT", "name": "%s", "ofType": null}}}}
        """
        .formatted(name, typeName);
  }

  private static String withArguments(String name) {
    return """
        {"name": "%s", "args": [{"name": "input"}],
         "type": {"kind": "SCALAR", "name": "String", "ofType": null}}
        """
        .formatted(name);
  }

  private static String fields(String... entries) {
    return "[" + String.join(",", entries) + "]";
  }

  @Test
  public void testSelectionDescendsIntoNestedObjects() throws Exception {
    FakeGraphQL graphQL =
        new FakeGraphQL(
            Map.of(
                "AppConfig", fields(scalar("appVersion"), object("visualConfig", "VisualConfig")),
                "VisualConfig",
                    fields(scalar("appTitle"), object("queriesTab", "QueriesTabConfig")),
                "QueriesTabConfig", fields(scalar("queriesTabResultSize"))));

    String selection = new SchemaIntrospector(graphQL).selectionSet("AppConfig", 2);

    assertEquals(
        selection, "{ appVersion visualConfig { appTitle queriesTab { queriesTabResultSize } } }");
    // One request per level, rather than one per type.
    assertEquals(graphQL.requestedTypes.size(), 3);
  }

  @Test
  public void testListsOfObjectsAndFieldsWithArgumentsAreSkipped() throws Exception {
    FakeGraphQL graphQL =
        new FakeGraphQL(
            Map.of(
                "AppConfig",
                fields(
                    scalar("appVersion"),
                    objectList("platformPrivileges", "Privilege"),
                    withArguments("latestProductUpdate")),
                "Privilege",
                fields(scalar("type"))));

    String selection = new SchemaIntrospector(graphQL).selectionSet("AppConfig", 2);

    assertEquals(selection, "{ appVersion }");
    assertFalse(
        graphQL.requestedTypes.stream().flatMap(List::stream).anyMatch("Privilege"::equals),
        "a list of objects should not be introspected at all");
  }

  @Test
  public void testDepthStopsTheDescent() throws Exception {
    FakeGraphQL graphQL =
        new FakeGraphQL(
            Map.of(
                "AppConfig", fields(object("visualConfig", "VisualConfig")),
                "VisualConfig",
                    fields(scalar("appTitle"), object("queriesTab", "QueriesTabConfig")),
                "QueriesTabConfig", fields(scalar("queriesTabResultSize"))));

    String selection = new SchemaIntrospector(graphQL).selectionSet("AppConfig", 1);

    assertEquals(selection, "{ visualConfig { appTitle } }");
  }

  @Test
  public void testUnknownTypeIsSurvivable() throws Exception {
    // The server knows AppConfig but not the type one of its fields points at.
    FakeGraphQL graphQL =
        new FakeGraphQL(
            Map.of("AppConfig", fields(scalar("appVersion"), object("future", "FutureConfig"))));

    String selection = new SchemaIntrospector(graphQL).selectionSet("AppConfig", 2);

    assertEquals(selection, "{ appVersion }");
  }

  @Test
  public void testIntrospectionDisabledIsReported() {
    FakeGraphQL graphQL = new FakeGraphQL(Map.of());

    SchemaIntrospector.UnavailableException thrown =
        expectThrows(
            SchemaIntrospector.UnavailableException.class,
            () -> new SchemaIntrospector(graphQL).selectionSet("AppConfig", 2));
    assertTrue(thrown.getMessage().contains("AppConfig"));
  }
}
