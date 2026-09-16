package io.datahubproject.cli;

import io.datahubproject.cli.client.GraphQLClient;
import io.datahubproject.cli.command.PoliciesCommand;
import io.datahubproject.cli.config.CliConfig;
import java.nio.file.Path;
import java.time.Duration;
import picocli.CommandLine;

/** Entry point for the DataHub developer / DevOps CLI. */
@CommandLine.Command(
    name = "datahub-cli",
    mixinStandardHelpOptions = true,
    version = "datahub-cli 0.1.0",
    description = "Local CLI for inspecting a DataHub instance.",
    subcommands = {PoliciesCommand.class})
public class DataHubCli implements Runnable {

  @CommandLine.Option(
      names = "--url",
      description =
          "GMS URL. Falls back to $"
              + CliConfig.GMS_URL_ENV
              + ", then the config file, then "
              + CliConfig.DEFAULT_GMS_URL
              + ".")
  private String url;

  @CommandLine.Option(
      names = "--token",
      description =
          "Access token. Falls back to $" + CliConfig.GMS_TOKEN_ENV + ", then the config file.")
  private String token;

  @CommandLine.Option(
      names = "--config",
      description =
          "Path to a YAML config file. Default: ./datahub-cli.yaml, then ~/.datahub/datahub-cli.yaml.")
  private Path configFile;

  @CommandLine.Option(
      names = "--timeout-seconds",
      description = "Request timeout in seconds. Default: ${DEFAULT-VALUE}.")
  private int timeoutSeconds = 30;

  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

  public CommandLine.Model.CommandSpec spec() {
    return spec;
  }

  public CliConfig config() {
    return CliConfig.resolve(url, token, configFile, System.getenv());
  }

  public GraphQLClient newGraphQLClient() {
    return new GraphQLClient(config(), Duration.ofSeconds(timeoutSeconds));
  }

  @Override
  public void run() {
    // No subcommand given: show usage rather than doing nothing.
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static void main(String[] args) {
    CommandLine commandLine =
        new CommandLine(new DataHubCli()).setCaseInsensitiveEnumValuesAllowed(true);
    commandLine.setExecutionExceptionHandler(
        (ex, cmd, parseResult) -> {
          // Users get the message; the stack trace only on demand.
          cmd.getErr().println(cmd.getColorScheme().errorText("Error: " + ex.getMessage()));
          if (System.getenv("DATAHUB_CLI_DEBUG") != null) {
            ex.printStackTrace(cmd.getErr());
          }
          return CommandLine.ExitCode.SOFTWARE;
        });
    System.exit(commandLine.execute(args));
  }
}
