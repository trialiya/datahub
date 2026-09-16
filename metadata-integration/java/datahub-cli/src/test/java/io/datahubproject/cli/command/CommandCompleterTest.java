package io.datahubproject.cli.command;

import static org.testng.Assert.assertTrue;

import io.datahubproject.cli.DataHubCli;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.testng.annotations.Test;
import picocli.CommandLine;

public class CommandCompleterTest {

  /** Minimal ParsedLine: the completer only reads words() and wordIndex(). */
  private record Line(List<String> words, int wordIndex) implements ParsedLine {
    @Override
    public String word() {
      return wordIndex < words.size() ? words.get(wordIndex) : "";
    }

    @Override
    public int wordCursor() {
      return word().length();
    }

    @Override
    public String line() {
      return String.join(" ", words);
    }

    @Override
    public int cursor() {
      return line().length();
    }
  }

  private static List<String> complete(List<String> words, int wordIndex) {
    List<Candidate> candidates = new ArrayList<>();
    new CommandCompleter(() -> new CommandLine(new DataHubCli()), List.of("help", "exit"))
        .complete(null, new Line(words, wordIndex), candidates);
    return candidates.stream().map(Candidate::value).toList();
  }

  @Test
  public void testCompletesSubcommandsAndBuiltinsAtStartOfLine() {
    List<String> candidates = complete(List.of(""), 0);

    assertTrue(candidates.contains("policies"), candidates.toString());
    assertTrue(candidates.contains("shell"), candidates.toString());
    assertTrue(candidates.contains("help"), candidates.toString());
  }

  @Test
  public void testCompletesOptionsOfTheNamedSubcommand() {
    List<String> candidates = complete(List.of("policies", ""), 1);

    assertTrue(candidates.contains("--state"), candidates.toString());
    assertTrue(candidates.contains("--privilege"), candidates.toString());
  }

  @Test
  public void testCompletesEnumValuesAfterTheirOption() {
    List<String> candidates = complete(List.of("policies", "--format", ""), 2);

    assertTrue(candidates.contains("CSV"), candidates.toString());
    assertTrue(candidates.contains("JSON"), candidates.toString());
    // Option names must not be mixed into a value position.
    assertTrue(!candidates.contains("--state"), candidates.toString());
  }

  @Test
  public void testUnknownSubcommandCompletesToNothing() {
    assertTrue(complete(List.of("bogus", ""), 1).isEmpty());
  }
}
