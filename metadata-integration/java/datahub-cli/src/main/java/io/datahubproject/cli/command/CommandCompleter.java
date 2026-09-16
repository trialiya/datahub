package io.datahubproject.cli.command;

import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import picocli.CommandLine;

/**
 * TAB completion driven by the picocli command tree, so new commands and options become completable
 * without touching this class.
 */
public class CommandCompleter implements Completer {

  private final Supplier<CommandLine> commandLineSupplier;
  private final List<String> builtins;

  public CommandCompleter(Supplier<CommandLine> commandLineSupplier, List<String> builtins) {
    this.commandLineSupplier = commandLineSupplier;
    this.builtins = builtins;
  }

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    CommandLine root = commandLineSupplier.get();
    List<String> words = line.words();

    if (line.wordIndex() == 0) {
      root.getSubcommands().keySet().forEach(name -> candidates.add(new Candidate(name)));
      builtins.forEach(name -> candidates.add(new Candidate(name)));
      return;
    }

    CommandLine subcommand = root.getSubcommands().get(words.get(0));
    if (subcommand == null) {
      return;
    }
    CommandLine.Model.CommandSpec spec = subcommand.getCommandSpec();

    // Right after an option that declares candidates (enums, mostly), offer its values.
    CommandLine.Model.OptionSpec previousOption = spec.findOption(words.get(line.wordIndex() - 1));
    if (previousOption != null && previousOption.completionCandidates() != null) {
      previousOption.completionCandidates().forEach(value -> candidates.add(new Candidate(value)));
      return;
    }

    for (CommandLine.Model.OptionSpec option : spec.options()) {
      for (String name : option.names()) {
        candidates.add(new Candidate(name));
      }
    }
  }
}
