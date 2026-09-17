package io.datahubproject.cli.registry;

import java.util.List;
import java.util.TreeSet;

/**
 * The entity and aspect names offered by TAB completion.
 *
 * <p>An interface so the completer can be exercised without loading the registry, which parses
 * every aspect's schema and takes a couple of seconds.
 */
public interface ModelNames {

  List<String> entityNames();

  List<String> aspectNames(String entityName);

  /** Every aspect name in the model, for places where the entity type is not known yet. */
  List<String> allAspectNames();

  /** Names from the registry compiled into this CLI; empty when it cannot be loaded. */
  ModelNames FROM_LOCAL_REGISTRY =
      new ModelNames() {
        @Override
        public List<String> entityNames() {
          LocalEntityRegistry registry = LocalEntityRegistry.get();
          return registry == null ? List.of() : registry.entityNames();
        }

        @Override
        public List<String> aspectNames(String entityName) {
          LocalEntityRegistry registry = LocalEntityRegistry.get();
          return registry == null ? List.of() : registry.aspectNames(entityName);
        }

        @Override
        public List<String> allAspectNames() {
          LocalEntityRegistry registry = LocalEntityRegistry.get();
          if (registry == null) {
            return List.of();
          }
          TreeSet<String> names = new TreeSet<>();
          registry.entityNames().forEach(entity -> names.addAll(registry.aspectNames(entity)));
          return List.copyOf(names);
        }
      };
}
