package io.datahubproject.cli.registry;

import com.linkedin.metadata.authorization.PoliciesConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.TreeSet;

/**
 * The privilege catalogue compiled into this CLI, taken from GMS's own {@code PoliciesConfig}.
 *
 * <p>Unlike the entity registry this is cheap to load (it parses only the policy schemas), but it
 * is still the catalogue of the build the CLI came from, so it is used for completion, not
 * validation.
 */
public final class Privileges {

  private Privileges() {}

  /** Every privilege type known to this build, sorted and de-duplicated. */
  public static List<String> names() {
    TreeSet<String> names = new TreeSet<>(fromPolicyBuilderGroups());
    names.addAll(fromDeclaredConstants());
    return List.copyOf(names);
  }

  /**
   * The privileges reachable through the grouped lists: the platform ones plus everything attached
   * to a resource type. This is exactly what the Policy Builder offers and what {@code
   * appConfig.policiesConfig} returns over GraphQL.
   */
  private static List<String> fromPolicyBuilderGroups() {
    TreeSet<String> names = new TreeSet<>();
    PoliciesConfig.PLATFORM_PRIVILEGES.forEach(privilege -> names.add(privilege.getType()));
    PoliciesConfig.RESOURCE_PRIVILEGES.forEach(
        resource -> resource.getPrivileges().forEach(privilege -> names.add(privilege.getType())));
    return List.copyOf(names);
  }

  /**
   * Every {@code Privilege} constant declared on {@code PoliciesConfig}, whether or not it belongs
   * to a group.
   *
   * <p>Sixteen privileges are declared but attached to no group, among them the operational ones a
   * DevOps user is most likely to look for: RESTORE_INDICES, TRUNCATE_TIMESERIES_INDEX,
   * ES_EXPLAIN_QUERY, GET_ES_TASK_STATUS, SET_WRITEABLE and APPLY_RETENTION. They cannot be granted
   * through the Policy Builder, only by writing the policy directly, so a catalogue built from the
   * groups alone would hide them from completion. Some of those constants are package-private,
   * hence the reflection; the fields are static and the class sits in the unnamed module, so {@code
   * trySetAccessible} succeeds. Should a future JDK or a modular classpath refuse, the grouped list
   * above still stands on its own.
   */
  private static List<String> fromDeclaredConstants() {
    TreeSet<String> names = new TreeSet<>();
    for (Field field : PoliciesConfig.class.getDeclaredFields()) {
      if (field.getType() != PoliciesConfig.Privilege.class
          || !Modifier.isStatic(field.getModifiers())
          || !field.trySetAccessible()) {
        continue;
      }
      try {
        PoliciesConfig.Privilege privilege = (PoliciesConfig.Privilege) field.get(null);
        if (privilege != null) {
          names.add(privilege.getType());
        }
      } catch (IllegalAccessException e) {
        // Nothing to recover: the grouped list is the fallback.
      }
    }
    return List.copyOf(names);
  }
}
