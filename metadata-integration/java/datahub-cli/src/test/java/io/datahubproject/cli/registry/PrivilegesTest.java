package io.datahubproject.cli.registry;

import static org.testng.Assert.assertTrue;

import com.linkedin.metadata.authorization.PoliciesConfig;
import java.util.List;
import java.util.TreeSet;
import org.testng.annotations.Test;

public class PrivilegesTest {

  @Test
  public void testCatalogueCoversPrivilegesOutsideThePolicyBuilder() {
    List<String> names = Privileges.names();

    // The operational privileges belong to no resource group, so a catalogue built from the
    // grouped lists alone -- which is also all that appConfig.policiesConfig returns -- omits
    // them. They are exactly the ones a DevOps user reaches for.
    assertTrue(names.contains("RESTORE_INDICES_PRIVILEGE"), "missing RESTORE_INDICES_PRIVILEGE");
    assertTrue(
        names.contains("TRUNCATE_TIMESERIES_INDEX_PRIVILEGE"),
        "missing TRUNCATE_TIMESERIES_INDEX_PRIVILEGE");
    assertTrue(names.contains("ES_EXPLAIN_QUERY_PRIVILEGE"), "missing ES_EXPLAIN_QUERY_PRIVILEGE");
  }

  @Test
  public void testCatalogueIsASupersetOfTheGroupedLists() {
    TreeSet<String> grouped = new TreeSet<>();
    PoliciesConfig.PLATFORM_PRIVILEGES.forEach(privilege -> grouped.add(privilege.getType()));
    PoliciesConfig.RESOURCE_PRIVILEGES.forEach(
        resource ->
            resource.getPrivileges().forEach(privilege -> grouped.add(privilege.getType())));

    List<String> names = Privileges.names();
    assertTrue(names.containsAll(grouped));
    assertTrue(names.size() > grouped.size(), "reflection added nothing to the grouped lists");
  }
}
