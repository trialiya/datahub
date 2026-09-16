package io.datahubproject.cli.client;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import org.testng.annotations.Test;

public class UrnsTest {

  @Test
  public void testEntityTypeOfNestedUrn() {
    assertEquals(
        Urns.entityType("urn:li:dataset:(urn:li:dataPlatform:hive,my_db.my_table,PROD)"),
        "dataset");
  }

  @Test
  public void testEntityTypeOfFlatUrn() {
    assertEquals(Urns.entityType("urn:li:corpuser:alice"), "corpuser");
  }

  @Test
  public void testNonUrnIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> Urns.entityType("not-a-urn"));
    assertThrows(IllegalArgumentException.class, () -> Urns.entityType("urn:li:"));
  }
}
