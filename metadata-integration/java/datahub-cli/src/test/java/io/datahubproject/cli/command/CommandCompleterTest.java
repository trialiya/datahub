package io.datahubproject.cli.command;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.datahubproject.cli.DataHubCli;
import io.datahubproject.cli.registry.ModelNames;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.testng.annotations.Test;
import picocli.CommandLine;

public class CommandCompleterTest {

  /** Stands in for the registry, which takes seconds to load and is not what these tests check. */
  private static final ModelNames MODEL_NAMES =
      new ModelNames() {
        @Override
        public List<String> entityNames() {
          return List.of("dataset", "dataHubPolicy");
        }

        @Override
        public List<String> aspectNames(String entityName) {
          return switch (entityName) {
            case "dataset" -> List.of("datasetKey", "ownership");
            case "dataHubPolicy" -> List.of("dataHubPolicyInfo", "dataHubPolicyKey");
            default -> List.of();
          };
        }

        @Override
        public List<String> allAspectNames() {
          return List.of("dataHubPolicyInfo", "dataHubPolicyKey", "datasetKey", "ownership");
        }
      };

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
    new CommandCompleter(
            () -> new CommandLine(new DataHubCli()), List.of("help", "exit"), MODEL_NAMES)
        .complete(null, new Line(words, wordIndex), candidates);
    return candidates.stream().map(Candidate::value).toList();
  }

  @Test
  public void testCompletesSubcommandsAndBuiltinsAtStartOfLine() {
    List<String> candidates = complete(List.of(""), 0);

    assertTrue(candidates.contains("policies"), candidates.toString());
    assertTrue(candidates.contains("aspect"), candidates.toString());
    assertTrue(candidates.contains("help"), candidates.toString());
  }

  @Test
  public void testCompletesOptionsWhenTheWordLooksLikeOne() {
    List<String> candidates = complete(List.of("policies", "-"), 1);

    assertTrue(candidates.contains("--state"), candidates.toString());
    assertTrue(candidates.contains("--privilege"), candidates.toString());
  }

  @Test
  public void testCompletesEnumValuesAfterTheirOption() {
    List<String> candidates = complete(List.of("policies", "--format", ""), 2);

    assertTrue(candidates.contains("CSV"), candidates.toString());
    assertFalse(candidates.contains("--state"), candidates.toString());
  }

  @Test
  public void testUrnPositionOffersEntityNames() {
    List<String> candidates = complete(List.of("aspect", ""), 1);

    assertEquals(candidates, List.of("urn:li:dataset:", "urn:li:dataHubPolicy:"));
  }

  @Test
  public void testAspectPositionIsNarrowedByTheUrnsEntityType() {
    List<String> candidates = complete(List.of("aspect", "urn:li:dataHubPolicy:test", ""), 2);

    assertEquals(candidates, List.of("dataHubPolicyInfo", "dataHubPolicyKey"));
  }

  @Test
  public void testOptionsBeforeThePositionalDoNotShiftIt() {
    // "--version 2" consumes a value, so the URN is still the first positional after it.
    List<String> candidates =
        complete(List.of("aspect", "--version", "2", "urn:li:dataset:x", ""), 4);

    assertEquals(candidates, List.of("datasetKey", "ownership"));
  }

  @Test
  public void testAspectOptionOfSearchOffersEveryAspectName() {
    List<String> candidates = complete(List.of("search", "--aspect", ""), 2);

    assertTrue(candidates.contains("ownership"), candidates.toString());
    assertTrue(candidates.contains("dataHubPolicyInfo"), candidates.toString());
  }

  @Test
  public void testUnknownSubcommandCompletesToNothing() {
    assertTrue(complete(List.of("bogus", ""), 1).isEmpty());
  }
}
