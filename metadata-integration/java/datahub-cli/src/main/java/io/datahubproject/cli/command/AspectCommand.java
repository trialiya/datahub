package io.datahubproject.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.client.AspectClient;
import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.client.Urns;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(name = "aspect", description = "Fetch one aspect of one entity.")
public class AspectCommand implements Callable<Integer> {

  @CommandLine.ParentCommand private DataHubCli parent;

  @CommandLine.Parameters(index = "0", paramLabel = "<urn>", description = "The entity URN.")
  private String urn;

  @CommandLine.Parameters(
      index = "1",
      paramLabel = "<aspect>",
      description = "The aspect name, e.g. ownership.")
  private String aspectName;

  @CommandLine.Option(
      names = "--version",
      description = "Aspect version; 0 (default) is the latest.")
  private long version = 0;

  @CommandLine.Option(
      names = "--system-metadata",
      description = "Also print the aspect's system metadata.")
  private boolean systemMetadata;

  @CommandLine.Option(
      names = "--list",
      description = "List the aspect names available for the URN's entity type and exit.")
  private boolean list;

  @Override
  public Integer call() throws Exception {
    PrintWriter out = parent.spec().commandLine().getOut();
    String entityType = Urns.entityType(urn);

    try (DataHubHttpClient http = parent.newHttpClient()) {
      AspectClient client = new AspectClient(http);
      List<String> knownAspects = client.listAspectNames(entityType);

      if (list) {
        printAspectList(out, entityType, knownAspects);
        return 0;
      }

      // The registry is only readable with MANAGE_SYSTEM_OPERATIONS_PRIVILEGE. When it is not,
      // skip validation and let the fetch itself report an unknown aspect.
      if (knownAspects != null && !knownAspects.contains(aspectName)) {
        parent
            .spec()
            .commandLine()
            .getErr()
            .println(
                "Unknown aspect '"
                    + aspectName
                    + "' for entity type '"
                    + entityType
                    + "'. Known aspects: "
                    + String.join(", ", knownAspects));
        return CommandLine.ExitCode.USAGE;
      }

      AspectClient.AspectPayload payload =
          client.getAspect(urn, aspectName, version, systemMetadata);

      out.println(
          http.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload.value()));
      JsonNode meta = payload.systemMetadata();
      if (systemMetadata && meta != null && !meta.isNull()) {
        out.println();
        out.println("systemMetadata:");
        out.println(http.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(meta));
      }
    } catch (DataHubHttpClient.NotFoundException e) {
      parent
          .spec()
          .commandLine()
          .getErr()
          .println("No aspect '" + aspectName + "' on " + urn + " (HTTP 404).");
      return CommandLine.ExitCode.SOFTWARE;
    }

    out.flush();
    return 0;
  }

  private void printAspectList(PrintWriter out, String entityType, List<String> knownAspects) {
    if (knownAspects == null) {
      out.println(
          "Cannot read the entity registry: the token lacks MANAGE_SYSTEM_OPERATIONS_PRIVILEGE.");
      return;
    }
    knownAspects.forEach(out::println);
    out.println();
    out.printf("%d aspects for entity type '%s'%n", knownAspects.size(), entityType);
  }
}
