package io.datahubproject.cli.command;

import io.datahubproject.cli.DataHubCli;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine;

@CommandLine.Command(
    name = "shell",
    description = "Start an interactive shell with history and TAB completion.")
public class ShellCommand implements Callable<Integer> {

  private static final String HISTORY_FILE = ".datahub/datahub-cli-history";
  private static final List<String> BUILTINS = List.of("help", "exit", "quit");

  @CommandLine.ParentCommand private DataHubCli parent;

  @Override
  public Integer call() throws IOException {
    try (Terminal terminal =
        TerminalBuilder.builder()
            .name("datahub-cli")
            // Fall back to a dumb terminal instead of failing when there is no TTY, so the shell
            // still works when input is piped.
            .dumb(true)
            .build()) {

      LineReader reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .appName("datahub-cli")
              .completer(new CommandCompleter(this::newCommandLine, BUILTINS))
              .variable(
                  LineReader.HISTORY_FILE,
                  Path.of(System.getProperty("user.home", ""), HISTORY_FILE))
              .build();

      terminal
          .writer()
          .println(
              "DataHub CLI · connected to "
                  + parent.config().gmsUrl()
                  + "\nType 'help' for commands, TAB to complete, Ctrl+D to exit.");
      terminal.writer().flush();

      runLoop(reader);
    }
    return 0;
  }

  private void runLoop(LineReader reader) {
    while (true) {
      String line;
      try {
        line = reader.readLine("datahub> ");
      } catch (UserInterruptException e) {
        // Ctrl+C abandons the current line but keeps the session.
        continue;
      } catch (EndOfFileException e) {
        return;
      }

      if (line == null || line.isBlank()) {
        continue;
      }

      List<String> words =
          reader.getParser().parse(line, 0).words().stream()
              .filter(word -> !word.isEmpty())
              .toList();
      if (words.isEmpty()) {
        continue;
      }

      String first = words.get(0).toLowerCase(Locale.ROOT);
      if (first.equals("exit") || first.equals("quit")) {
        return;
      }
      if (first.equals("help")) {
        newCommandLine().usage(reader.getTerminal().writer());
        reader.getTerminal().writer().flush();
        continue;
      }
      if (first.equals("shell")) {
        reader.getTerminal().writer().println("Already in a shell.");
        reader.getTerminal().writer().flush();
        continue;
      }

      // A fresh CommandLine per line: picocli does not reset option fields between executions, so
      // reusing one would let an option from a previous command leak into the next.
      newCommandLine().execute(words.toArray(new String[0]));
    }
  }

  private CommandLine newCommandLine() {
    DataHubCli command = new DataHubCli();
    command.inheritConnectionFrom(parent);
    return DataHubCli.configure(new CommandLine(command));
  }
}
