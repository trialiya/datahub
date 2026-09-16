package io.datahubproject.cli;

import io.datahubproject.cli.client.DataHubHttpClient;
import io.datahubproject.cli.command.AspectCommand;
import io.datahubproject.cli.command.PoliciesCommand;
import io.datahubproject.cli.command.SearchCommand;
import io.datahubproject.cli.command.ShellCommand;
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
    subcommands = {
      PoliciesCommand.class,
      AspectCommand.class,
      SearchCommand.class,
      ShellCommand.class
    })
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

  public DataHubHttpClient newHttpClient() {
    return new DataHubHttpClient(config(), Duration.ofSeconds(timeoutSeconds));
  }

  /**
   * Copies the connection settings of an outer invocation, so commands run inside the interactive
   * shell reach the same instance as the {@code shell} command itself.
   */
  public void inheritConnectionFrom(DataHubCli other) {
    this.url = other.url;
    this.token = other.token;
    this.configFile = other.configFile;
    this.timeoutSeconds = other.timeoutSeconds;
  }

  @Override
  public void run() {
    // No subcommand given: show usage rather than doing nothing.
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  /** Applies the settings shared by the one-shot entry point and the interactive shell. */
  public static CommandLine configure(CommandLine commandLine) {
    return commandLine
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(
            (ex, cmd, parseResult) -> {
              // Users get the message; the stack trace only on demand.
              cmd.getErr().println(cmd.getColorScheme().errorText("Error: " + ex.getMessage()));
              if (System.getenv("DATAHUB_CLI_DEBUG") != null) {
                ex.printStackTrace(cmd.getErr());
              }
              return CommandLine.ExitCode.SOFTWARE;
            });
  }

  public static void main(String[] args) {
    System.exit(configure(new CommandLine(new DataHubCli())).execute(args));
  }
}
