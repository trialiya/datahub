package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.datahubproject.cli.search.FilterRule;
import io.datahubproject.cli.search.WhereParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Searches entities through the GraphQL {@code scrollAcrossEntities} query. */
public class SearchClient {

  private static final String SCROLL_QUERY =
      """
      query scrollAcrossEntities($input: ScrollAcrossEntitiesInput!) {
        scrollAcrossEntities(input: $input) {
          nextScrollId
          count
          total
          searchResults {
            entity {
              urn
              type
            }
          }
        }
      }
      """;

  private final GraphQLClient graphQLClient;

  public SearchClient(GraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  /** One hit: the CLI only renders the identity of the matched entity. */
  public record Hit(String urn, String type) {}

  public record Results(List<Hit> hits, int total) {}

  /**
   * Runs one page of a search.
   *
   * @param query the free-text query; "*" matches everything
   * @param filters parsed {@code --where} filters, or null for none
   * @param limit maximum hits to return
   */
  public Results search(String query, WhereParser.Filters filters, int limit) throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("query", query);
    input.put("count", limit);

    if (filters != null) {
      if (!filters.entityTypes().isEmpty()) {
        input.put("types", filters.entityTypes());
      }
      List<Map<String, Object>> orFilters = new ArrayList<>();
      for (List<FilterRule> clause : filters.orFilters()) {
        if (clause.isEmpty()) {
          continue;
        }
        orFilters.add(Map.of("and", clause.stream().map(FilterRule::toGraphQL).toList()));
      }
      if (!orFilters.isEmpty()) {
        input.put("orFilters", orFilters);
      }
    }

    JsonNode result =
        graphQLClient.query(SCROLL_QUERY, Map.of("input", input)).path("scrollAcrossEntities");

    List<Hit> hits = new ArrayList<>();
    result
        .path("searchResults")
        .forEach(
            node -> {
              JsonNode entity = node.path("entity");
              hits.add(new Hit(entity.path("urn").asText(""), entity.path("type").asText("")));
            });
    return new Results(hits, result.path("total").asInt(0));
  }
}
