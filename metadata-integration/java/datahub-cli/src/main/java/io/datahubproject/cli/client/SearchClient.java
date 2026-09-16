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

  /**
   * Server-side page size; a smaller {@code limit} shrinks it so the last page is not oversized.
   */
  private static final int PAGE_SIZE = 100;

  /** How long GMS keeps the scroll point-in-time alive between pages. */
  private static final String KEEP_ALIVE = "5m";

  /** One hit: the CLI only renders the identity of the matched entity. */
  public record Hit(String urn, String type) {}

  public record Results(List<Hit> hits, int total) {}

  /**
   * Runs a search, following the scroll cursor until {@code limit} hits are collected or the server
   * runs out of results.
   *
   * @param query the free-text query; "*" matches everything
   * @param filters parsed {@code --where} filters, or null for none
   * @param limit maximum hits to return, or 0 for every match
   */
  public Results searchAll(String query, WhereParser.Filters filters, int limit)
      throws IOException {
    List<Hit> hits = new ArrayList<>();
    String scrollId = null;
    int total = 0;

    while (true) {
      int pageSize = limit > 0 ? Math.min(PAGE_SIZE, limit - hits.size()) : PAGE_SIZE;
      Page page = searchPage(query, filters, pageSize, scrollId);
      total = page.total();
      hits.addAll(page.hits());
      scrollId = page.nextScrollId();

      // Stop on a null cursor (no further pages) and on an empty page, which would otherwise spin
      // forever if the server kept handing back a cursor without results.
      if (scrollId == null || page.hits().isEmpty()) {
        break;
      }
      if (limit > 0 && hits.size() >= limit) {
        break;
      }
    }

    return new Results(hits, total);
  }

  private record Page(List<Hit> hits, int total, String nextScrollId) {}

  private Page searchPage(String query, WhereParser.Filters filters, int count, String scrollId)
      throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("query", query);
    input.put("count", count);
    if (scrollId != null) {
      input.put("scrollId", scrollId);
      input.put("keepAlive", KEEP_ALIVE);
    }

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

    JsonNode nextScrollId = result.get("nextScrollId");
    return new Page(
        hits,
        result.path("total").asInt(0),
        nextScrollId == null || nextScrollId.isNull() ? null : nextScrollId.asText());
  }
}
