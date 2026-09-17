package io.datahubproject.cli.command;

import io.datahubproject.cli.client.Urns;
import io.datahubproject.cli.registry.ModelNames;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import picocli.CommandLine;

/**
 * TAB completion driven by the picocli command tree, so new commands and options become completable
 * without touching this class, plus entity and aspect names from the model registry.
 */
public class CommandCompleter implements Completer {

  private static final String URN_PREFIX = "urn:li:";

  private final Supplier<CommandLine> commandLineSupplier;
  private final List<String> builtins;
  private final ModelNames modelNames;

  public CommandCompleter(
      Supplier<CommandLine> commandLineSupplier, List<String> builtins, ModelNames modelNames) {
    this.commandLineSupplier = commandLineSupplier;
    this.builtins = builtins;
    this.modelNames = modelNames;
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

    String subcommandName = words.get(0);
    CommandLine subcommand = root.getSubcommands().get(subcommandName);
    if (subcommand == null) {
      return;
    }
    CommandLine.Model.CommandSpec spec = subcommand.getCommandSpec();
    String previous = words.get(line.wordIndex() - 1);
    String current = line.wordIndex() < words.size() ? words.get(line.wordIndex()) : "";

    // Right after an option, offer its values: enum constants from picocli, model names for the
    // options that take them.
    CommandLine.Model.OptionSpec previousOption = spec.findOption(previous);
    if (previousOption != null && takesValue(previousOption)) {
      completeOptionValue(previousOption, candidates);
      return;
    }

    if (current.startsWith("-")) {
      addOptionNames(spec, candidates);
      return;
    }

    if (!completePositional(subcommandName, spec, words, line.wordIndex(), candidates)) {
      addOptionNames(spec, candidates);
    }
  }

  /**
   * Completes a positional parameter of a known command.
   *
   * @return whether anything was offered
   */
  private boolean completePositional(
      String subcommandName,
      CommandLine.Model.CommandSpec spec,
      List<String> words,
      int wordIndex,
      List<Candidate> candidates) {
    if (!"aspect".equals(subcommandName)) {
      return false;
    }
    List<String> positionals = positionalsBefore(spec, words, wordIndex);

    if (positionals.isEmpty()) {
      // The URN position: offer "urn:li:<entity>:" so the entity type completes in one step.
      modelNames
          .entityNames()
          .forEach(name -> candidates.add(new Candidate(URN_PREFIX + name + ":")));
      return true;
    }
    if (positionals.size() == 1) {
      // The aspect position: narrow to the aspects of the entity type already typed in the URN.
      List<String> aspects = aspectsForUrn(positionals.get(0));
      aspects.forEach(name -> candidates.add(new Candidate(name)));
      return !aspects.isEmpty();
    }
    return false;
  }

  private List<String> aspectsForUrn(String urn) {
    try {
      return modelNames.aspectNames(Urns.entityType(urn));
    } catch (IllegalArgumentException e) {
      // Not a URN yet; there is nothing to narrow by.
      return List.of();
    }
  }

  /** The positional words typed so far, skipping options and the values they consume. */
  private List<String> positionalsBefore(
      CommandLine.Model.CommandSpec spec, List<String> words, int wordIndex) {
    List<String> positionals = new ArrayList<>();
    for (int i = 1; i < wordIndex && i < words.size(); i++) {
      String word = words.get(i);
      if (word.startsWith("-")) {
        CommandLine.Model.OptionSpec option = spec.findOption(word);
        if (option != null && takesValue(option)) {
          i++;
        }
        continue;
      }
      positionals.add(word);
    }
    return positionals;
  }

  private void completeOptionValue(
      CommandLine.Model.OptionSpec option, List<Candidate> candidates) {
    if (isAspectOption(option)) {
      modelNames.allAspectNames().forEach(name -> candidates.add(new Candidate(name)));
      return;
    }
    if (option.completionCandidates() != null) {
      option.completionCandidates().forEach(value -> candidates.add(new Candidate(value)));
    }
  }

  private static boolean isAspectOption(CommandLine.Model.OptionSpec option) {
    for (String name : option.names()) {
      if ("--aspect".equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean takesValue(CommandLine.Model.OptionSpec option) {
    return option.arity().min() > 0;
  }

  private static void addOptionNames(
      CommandLine.Model.CommandSpec spec, List<Candidate> candidates) {
    for (CommandLine.Model.OptionSpec option : spec.options()) {
      for (String name : option.names()) {
        candidates.add(new Candidate(name));
      }
    }
  }
}
