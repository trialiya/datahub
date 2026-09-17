package io.datahubproject.cli.registry;

import com.linkedin.metadata.authorization.PoliciesConfig;
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
    TreeSet<String> names = new TreeSet<>();
    PoliciesConfig.PLATFORM_PRIVILEGES.forEach(privilege -> names.add(privilege.getType()));
    PoliciesConfig.COMMON_ENTITY_PRIVILEGES.forEach(privilege -> names.add(privilege.getType()));
    PoliciesConfig.RESOURCE_PRIVILEGES.forEach(
        resource -> resource.getPrivileges().forEach(privilege -> names.add(privilege.getType())));
    return List.copyOf(names);
  }
}
