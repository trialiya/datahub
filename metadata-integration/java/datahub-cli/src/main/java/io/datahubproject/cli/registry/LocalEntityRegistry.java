package io.datahubproject.cli.registry;

import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.ConfigEntityRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The entity registry compiled into this CLI, read from the {@code entity-registry.yml} that ships
 * inside metadata-models.
 *
 * <p>It exists for TAB completion, which has to answer instantly and offline; correctness checks
 * still go to the server, whose registry is the one that actually applies and which may carry
 * custom models this build knows nothing about.
 *
 * <p>Loading parses every aspect's PDL schema, so it is deferred until the first completion and the
 * result is kept for the session.
 */
public final class LocalEntityRegistry {

  private static final String REGISTRY_RESOURCE = "entity-registry.yml";

  private static volatile LocalEntityRegistry instance;

  private final ConfigEntityRegistry registry;

  private LocalEntityRegistry(ConfigEntityRegistry registry) {
    this.registry = registry;
  }

  /** Returns the registry, or null when it cannot be loaded; completion then simply offers less. */
  public static LocalEntityRegistry get() {
    LocalEntityRegistry local = instance;
    if (local == null) {
      synchronized (LocalEntityRegistry.class) {
        local = instance;
        if (local == null) {
          local = load();
          instance = local;
        }
      }
    }
    return local;
  }

  private static LocalEntityRegistry load() {
    // Pegasus parses PDL with a generated ANTLR parser whose tool version predates its runtime, so
    // loading prints version-mismatch notices to stderr. They are harmless and would otherwise land
    // in the middle of the shell's output. The replacement forwards every other line, because the
    // load runs on a background thread and must not swallow what the foreground writes meanwhile.
    PrintStream stderr = System.err;
    try (InputStream in =
        LocalEntityRegistry.class.getClassLoader().getResourceAsStream(REGISTRY_RESOURCE)) {
      if (in == null) {
        return null;
      }
      System.setErr(filtering(stderr));
      return new LocalEntityRegistry(new ConfigEntityRegistry(in));
    } catch (Exception e) {
      return null;
    } finally {
      System.err.flush();
      System.setErr(stderr);
    }
  }

  /** Prefixes of the library notices that loading emits and that the user has no use for. */
  private static final List<String> SUPPRESSED_PREFIXES = List.of("ANTLR ", "SLF4J");

  private static PrintStream filtering(PrintStream delegate) {
    OutputStream lineFilter =
        new OutputStream() {
          private final ByteArrayOutputStream line = new ByteArrayOutputStream();

          @Override
          public synchronized void write(int b) {
            if (b == '\n') {
              emit();
            } else {
              line.write(b);
            }
          }

          @Override
          public synchronized void flush() {
            // Deliberately not emitting a partial line: the notices arrive as several print
            // calls, and the PrintStream below auto-flushes after each one, so emitting here
            // would split one notice into several lines and only the first would be matched
            // against the prefixes. Whatever is left when stderr is restored is a fragment of
            // such a notice, and dropping it is the point of this stream.
            delegate.flush();
          }

          private void emit() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (SUPPRESSED_PREFIXES.stream().noneMatch(text::startsWith)) {
              delegate.println(text);
            }
          }
        };
    return new PrintStream(lineFilter, true, StandardCharsets.UTF_8);
  }

  /**
   * Entity names as they appear in URNs. The registry keys them lower-cased, but URNs use the
   * original spelling (for example {@code dataHubPolicy}), so the names come from the specs.
   */
  public List<String> entityNames() {
    return registry.getEntitySpecs().values().stream()
        .map(EntitySpec::getName)
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  /** Aspect names of one entity type, or an empty list when the type is unknown. */
  public List<String> aspectNames(String entityName) {
    Map<String, EntitySpec> specs = registry.getEntitySpecs();
    // getEntitySpec throws on unknown names, and the registry keys are lower-cased.
    EntitySpec spec = specs.get(entityName.toLowerCase(java.util.Locale.ROOT));
    if (spec == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    spec.getAspectSpecs().stream().map(AspectSpec::getName).forEach(names::add);
    names.sort(Comparator.naturalOrder());
    return names;
  }
}
