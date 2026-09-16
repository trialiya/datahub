package io.datahubproject.cli.command;

import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.SearchClient;
import io.datahubproject.cli.search.FilterRule;
import io.datahubproject.cli.search.WhereParser;
import java.io.PrintWriter;
import java.util.List;
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
          new SearchClient(new GraphQLClient(http)).search(query, filters, limit);

      if (results.hits().isEmpty()) {
        out.println("No matches.");
        return 0;
      }
      TableRenderer.render(
          out,
          HEADERS,
          results.hits().stream().map(hit -> List.of(hit.urn(), hit.type())).toList());
      out.println();
      out.printf("%d of %d matches · %s%n", results.hits().size(), results.total(), http.gmsUrl());
    }

    out.flush();
    return 0;
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
