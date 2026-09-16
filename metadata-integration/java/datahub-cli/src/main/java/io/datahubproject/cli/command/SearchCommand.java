package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.AspectClient;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.SearchClient;
import io.datahubproject.cli.search.FilterRule;
import io.datahubproject.cli.search.WhereParser;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "search",
    description = "Search entities, optionally filtered with a --where expression.")
public class SearchCommand implements Callable<Integer> {

  private static final List<String> HEADERS = List.of("URN", "TYPE");

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Parameters(
      index = "0",
      arity = "0..1",
      paramLabel = "<query>",
      description = "Free-text query. Default: ${DEFAULT-VALUE}.")
  private String query = "*";

  @CommandLine.Option(
      names = {"-w", "--where"},
      description =
          "Filter expression, e.g. \"entity_type = dataset AND platform IN (hive, snowflake)\". "
              + "Supports AND, NOT, IN and IS [NOT] NULL.")
  private String where;

  @CommandLine.Option(
      names = {"-n", "--limit"},
      description = "Maximum number of hits. Default: ${DEFAULT-VALUE}.")
  private int limit = 20;

  @CommandLine.Option(
      names = {"-a", "--aspect"},
      description = "Also fetch this aspect for every hit, batched through OpenAPI v3. Repeatable.")
  private List<String> aspects = List.of();

  @CommandLine.Option(
      names = "--all",
      description = "Follow the scroll cursor until every match is returned, ignoring --limit.")
  private boolean all;

  @CommandLine.Option(
      names = "--print-filters",
      description = "Print the compiled GraphQL orFilters instead of running the search.")
  private boolean printFilters;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();
    WhereParser.Filters filters = where == null ? null : WhereParser.parse(where);

    if (printFilters) {
      printCompiled(out, filters);
      return 0;
    }

    try (DataHubHttpClient http = parent.newHttpClient()) {
      SearchClient.Results results =
          new SearchClient(new GraphQLClient(http)).searchAll(query, filters, all ? 0 : limit);

      if (results.hits().isEmpty()) {
        out.println("No matches.");
        return 0;
      }

      if (aspects.isEmpty()) {
        TableRenderer.render(
            out,
            HEADERS,
            results.hits().stream().map(hit -> List.of(hit.urn(), hit.type())).toList());
      } else {
        printWithAspects(out, http, results);
      }

      out.println();
      out.printf("%d of %d matches · %s%n", results.hits().size(), results.total(), http.gmsUrl());
    }

    out.flush();
    return 0;
  }

  /**
   * Prints one JSON object per hit, so the result can be piped into jq. The aspects of every hit
   * are read in batches rather than one request per entity.
   */
  private void printWithAspects(
      PrintWriter out, DataHubHttpClient http, SearchClient.Results results) throws Exception {
    List<String> urns = results.hits().stream().map(SearchClient.Hit::urn).toList();
    Map<String, Map<String, JsonNode>> byUrn =
        new AspectClient(http).getAspectsBatch(urns, aspects);

    for (SearchClient.Hit hit : results.hits()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("urn", hit.urn());
      row.put("type", hit.type());
      row.put("aspects", byUrn.getOrDefault(hit.urn(), Map.of()));
      out.println(http.mapper().writeValueAsString(row));
    }
  }

  /** Shows what the expression compiled to, so the mapping to GMS filters can be checked. */
  private void printCompiled(PrintWriter out, WhereParser.Filters filters) {
    if (filters == null) {
      out.println("No --where expression given.");
      return;
    }
    if (!filters.entityTypes().isEmpty()) {
      out.println("types: " + filters.entityTypes());
    }
    out.println("orFilters:");
    for (List<FilterRule> clause : filters.orFilters()) {
      out.println("  - and: " + clause.stream().map(rule -> rule.toGraphQL().toString()).toList());
    }
  }
}
