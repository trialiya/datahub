package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.JsonFlattener;
import io.datahubproject.cli.client.SchemaIntrospector;
import io.datahubproject.cli.client.ServerConfigClient;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "server-info",
    description = "Show the version, deployment facts and feature configuration of an instance.")
public class ServerInfoCommand implements Callable<Integer> {

  private static final String MISSING = "-";

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Option(
      names = {"-f", "--format"},
      description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
  private OutputFormat format = OutputFormat.TABLE;

  @CommandLine.Option(
      names = "--section",
      description =
          "Only show keys starting with this prefix, e.g. appConfig.featureFlags or server.versions.")
  private String section;

  @CommandLine.Option(
      names = "--diff",
      description =
          "Compare against a JSON file previously written by `server-info -f json`, "
              + "and show only the keys that differ.")
  private Path diffAgainst;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();

    JsonNode config;
    try (DataHubHttpClient http = parent.newHttpClient()) {
      GraphQLClient graphQL = new GraphQLClient(http);
      config = new ServerConfigClient(http, graphQL, new SchemaIntrospector(graphQL)).fetch();
    }

    if (diffAgainst != null) {
      return printDiff(out, config);
    }

    if (format == OutputFormat.JSON) {
      // Unfiltered, so the output stays usable as the other side of a later --diff.
      out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(config));
      out.flush();
      return 0;
    }

    Map<String, String> flat = select(JsonFlattener.flatten(config));
    if (flat.isEmpty()) {
      out.println("No configuration keys matched.");
      out.flush();
      return 0;
    }
    List<List<String>> rows = new ArrayList<>();
    flat.forEach((key, value) -> rows.add(List.of(key, value)));
    if (format == OutputFormat.CSV) {
      out.println("KEY,VALUE");
      rows.forEach(row -> out.println(csvEscape(row.get(0)) + "," + csvEscape(row.get(1))));
    } else {
      TableRenderer.render(out, List.of("KEY", "VALUE"), rows);
      out.println();
      out.printf("%d keys · %s%n", rows.size(), parent.config().gmsUrl());
    }
    out.flush();
    return 0;
  }

  /**
   * @return 0 when the two sides agree, 1 when they differ, so the command can gate a deployment
   *     check
   */
  private Integer printDiff(PrintWriter out, JsonNode current) throws Exception {
    JsonNode other = new ObjectMapper().readTree(Files.readString(diffAgainst));
    Map<String, String[]> differences =
        JsonFlattener.diff(
            select(JsonFlattener.flatten(current)), select(JsonFlattener.flatten(other)));

    if (differences.isEmpty()) {
      out.println("No differences.");
      out.flush();
      return 0;
    }

    List<List<String>> rows = new ArrayList<>();
    differences.forEach(
        (key, values) ->
            rows.add(
                List.of(
                    key,
                    values[0] == null ? MISSING : values[0],
                    values[1] == null ? MISSING : values[1])));
    TableRenderer.render(
        out, List.of("KEY", parent.config().gmsUrl(), diffAgainst.toString()), rows);
    out.println();
    out.printf("%d keys differ%n", rows.size());
    out.flush();
    return 1;
  }

  private Map<String, String> select(Map<String, String> flat) {
    if (section == null) {
      return flat;
    }
    String prefix = section.toLowerCase(Locale.ROOT);
    flat.keySet().removeIf(key -> !key.toLowerCase(Locale.ROOT).startsWith(prefix));
    return flat;
  }

  private static String csvEscape(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }
}
