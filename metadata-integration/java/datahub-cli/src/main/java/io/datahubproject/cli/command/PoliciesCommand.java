package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.Policy;
import io.datahubproject.cli.client.PolicyClient;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "policies",
    description = "List DataHub policies and the privileges they grant.")
public class PoliciesCommand implements Callable<Integer> {

  /** Output shapes; CSV and JSON exist so results can be diffed between environments. */
  public enum Format {
    TABLE,
    CSV,
    JSON
  }

  private static final List<String> HEADERS =
      List.of("NAME", "TYPE", "STATE", "PRIVILEGES", "ACTORS");

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Option(
      names = {"-f", "--format"},
      description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
  private Format format = Format.TABLE;

  @CommandLine.Option(
      names = "--state",
      description = "Only show policies in this state, e.g. ACTIVE or INACTIVE.")
  private String state;

  @CommandLine.Option(
      names = "--type",
      description = "Only show policies of this type, e.g. METADATA or PLATFORM.")
  private String type;

  @CommandLine.Option(
      names = "--privilege",
      description = "Only show policies granting this privilege, e.g. MANAGE_POLICIES.")
  private String privilege;

  @CommandLine.Option(names = "--urn", description = "Show the policy URN instead of its name.")
  private boolean showUrn;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();

    List<Policy> policies;
    try (GraphQLClient graphQLClient = parent.newGraphQLClient()) {
      policies = new PolicyClient(graphQLClient).listPolicies();
    }

    List<Policy> filtered =
        policies.stream()
            .filter(policy -> state == null || state.equalsIgnoreCase(policy.state()))
            .filter(policy -> type == null || type.equalsIgnoreCase(policy.type()))
            .filter(
                policy -> privilege == null || containsIgnoreCase(policy.privileges(), privilege))
            .sorted(Comparator.comparing(Policy::name, String.CASE_INSENSITIVE_ORDER))
            .toList();

    switch (format) {
      case JSON ->
          out.println(
              new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(filtered));
      case CSV -> printCsv(out, filtered);
      case TABLE -> printTable(out, filtered, policies.size());
    }
    out.flush();
    return 0;
  }

  private void printTable(PrintWriter out, List<Policy> policies, int totalFetched) {
    if (policies.isEmpty()) {
      out.println("No policies matched.");
      return;
    }
    TableRenderer.render(out, HEADERS, policies.stream().map(this::toRow).toList());
    out.println();
    out.printf("%d of %d policies · %s%n", policies.size(), totalFetched, parent.config().gmsUrl());
  }

  private void printCsv(PrintWriter out, List<Policy> policies) {
    out.println(String.join(",", HEADERS));
    for (Policy policy : policies) {
      List<String> escaped = new ArrayList<>();
      toRow(policy).forEach(cell -> escaped.add(csvEscape(cell)));
      out.println(String.join(",", escaped));
    }
  }

  private List<String> toRow(Policy policy) {
    return List.of(
        showUrn ? policy.urn() : policy.name(),
        policy.type(),
        policy.state(),
        String.join(", ", policy.privileges()),
        policy.actors().summarize());
  }

  private static String csvEscape(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }

  private static boolean containsIgnoreCase(List<String> values, String needle) {
    String lowered = needle.toLowerCase(Locale.ROOT);
    return values.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(lowered));
  }
}
