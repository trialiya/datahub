package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.MeClient;
import io.datahubproject.cli.client.SchemaIntrospector;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "whoami",
    description =
        "Show who the configured token authenticates as and what the instance lets them do.")
public class WhoamiCommand implements Callable<Integer> {

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Option(
      names = {"-f", "--format"},
      description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
  private OutputFormat format = OutputFormat.TABLE;

  @CommandLine.Option(
      names = "--all",
      description = "List every platform privilege, not only the granted ones.")
  private boolean all;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();

    MeClient.Me me;
    try (DataHubHttpClient http = parent.newHttpClient()) {
      GraphQLClient graphQL = new GraphQLClient(http);
      me = new MeClient(graphQL, new SchemaIntrospector(graphQL)).me();
    }

    if (format == OutputFormat.JSON) {
      out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(me));
      out.flush();
      return 0;
    }

    printIdentity(out, me);
    printPrivileges(out, me);
    out.flush();
    return 0;
  }

  private void printIdentity(PrintWriter out, MeClient.Me me) {
    List<List<String>> rows = new ArrayList<>();
    addRow(rows, "urn", me.urn());
    addRow(rows, "username", me.username());
    addRow(rows, "displayName", me.displayName());
    addRow(rows, "email", me.email());
    addRow(rows, "title", me.title());
    addRow(rows, "nativeUser", me.nativeUser() == null ? null : String.valueOf(me.nativeUser()));
    addRow(rows, "gms", parent.config().gmsUrl());
    TableRenderer.render(out, List.of("FIELD", "VALUE"), rows);
  }

  private void printPrivileges(PrintWriter out, MeClient.Me me) {
    out.println();
    Map<String, Boolean> privileges = me.platformPrivileges();
    if (privileges == null) {
      out.println(
          "Platform privileges unavailable: this instance does not answer GraphQL introspection.");
      return;
    }

    List<List<String>> rows = new ArrayList<>();
    long granted = privileges.values().stream().filter(Boolean::booleanValue).count();
    privileges.forEach(
        (name, value) -> {
          if (all || value) {
            rows.add(List.of(name, value ? "yes" : "no"));
          }
        });

    if (rows.isEmpty()) {
      out.println("No platform privileges granted (of " + privileges.size() + ").");
      return;
    }
    TableRenderer.render(out, List.of("PLATFORM PRIVILEGE", "GRANTED"), rows);
    out.println();
    out.printf(
        "%d of %d granted%s%n", granted, privileges.size(), all ? "" : " · --all to see the rest");
  }

  private static void addRow(List<List<String>> rows, String field, String value) {
    if (value != null && !value.isBlank()) {
      rows.add(List.of(field, value));
    }
  }
}
