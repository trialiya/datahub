package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.client.PrivilegeClient;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "can",
    description =
        "Show the privileges an actor is granted, optionally on one resource, "
            + "and why policies denied the rest.")
public class CanCommand implements Callable<Integer> {

  /** Exit code for a --privilege check that came back denied, kept apart from ordinary errors. */
  private static final int NOT_GRANTED = 3;

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Parameters(
      index = "0",
      paramLabel = "<actorUrn>",
      description = "e.g. urn:li:corpuser:jdoe")
  private String actorUrn;

  @CommandLine.Option(
      names = "--on",
      paramLabel = "<resourceUrn>",
      description =
          "Evaluate against this resource. Without it, only the actor's platform-wide privileges "
              + "are returned.")
  private String resourceUrn;

  @CommandLine.Option(
      names = "--privilege",
      description =
          "Check a single privilege instead of listing them. Exits 0 when granted, "
              + NOT_GRANTED
              + " when not.")
  private String privilege;

  @CommandLine.Option(
      names = "--why",
      description =
          "Also show, per policy, the reason it did not grant anything. "
              + "Requires the MANAGE_POLICIES privilege; the server silently omits the reasons otherwise.")
  private boolean why;

  @CommandLine.Option(
      names = {"-f", "--format"},
      description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
  private OutputFormat format = OutputFormat.TABLE;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();

    PrivilegeClient.Grant grant;
    try (DataHubHttpClient http = parent.newHttpClient()) {
      grant =
          new PrivilegeClient(new GraphQLClient(http))
              .grantedPrivileges(actorUrn, resourceUrn, why);
    }

    if (format == OutputFormat.JSON) {
      out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(grant));
      out.flush();
      return privilege != null && !isGranted(grant) ? NOT_GRANTED : 0;
    }

    if (privilege != null) {
      boolean granted = isGranted(grant);
      out.printf(
          "%s %s %s%s%n",
          actorUrn,
          granted ? "is granted" : "is NOT granted",
          privilege.toUpperCase(Locale.ROOT),
          resourceUrn == null ? "" : " on " + resourceUrn);
      printDenials(out, grant);
      out.flush();
      return granted ? 0 : NOT_GRANTED;
    }

    if (grant.privileges().isEmpty()) {
      out.println("No privileges granted.");
    } else {
      List<List<String>> rows = new ArrayList<>();
      grant.privileges().forEach(granted -> rows.add(List.of(granted)));
      TableRenderer.render(out, List.of("PRIVILEGE"), rows);
      out.println();
      out.printf(
          "%d privileges · %s%s%n",
          grant.privileges().size(), actorUrn, resourceUrn == null ? "" : " on " + resourceUrn);
    }
    printDenials(out, grant);
    out.flush();
    return 0;
  }

  private void printDenials(PrintWriter out, PrivilegeClient.Grant grant) {
    if (!why) {
      return;
    }
    if (grant.denials() == null) {
      out.println();
      out.println(
          "Per-policy reasons were not returned: they require the MANAGE_POLICIES privilege.");
      return;
    }
    if (grant.denials().isEmpty()) {
      out.println();
      out.println("No policy reported a reason for denial.");
      return;
    }
    out.println();
    List<List<String>> rows = new ArrayList<>();
    grant.denials().forEach(denial -> rows.add(List.of(denial.policyName(), denial.reason())));
    TableRenderer.render(out, List.of("POLICY", "REASON FOR DENY"), rows);
  }

  private boolean isGranted(PrivilegeClient.Grant grant) {
    String wanted = privilege.toUpperCase(Locale.ROOT);
    return grant.privileges().stream().anyMatch(granted -> granted.equalsIgnoreCase(wanted));
  }
}
