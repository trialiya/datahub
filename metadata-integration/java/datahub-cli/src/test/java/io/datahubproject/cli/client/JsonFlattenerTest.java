package io.datahubproject.cli.client;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.testng.annotations.Test;

public class JsonFlattenerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void testNestedObjectsBecomeDottedKeys() throws Exception {
    Map<String, String> flat =
        JsonFlattener.flatten(
            MAPPER.readTree(
                """
                {"appConfig": {"featureFlags": {"showBrowseV2": true},
                               "authConfig": {"allowedAccessTokenDurations": ["P1D", "P30D"]}}}
                """));

    assertEquals(flat.get("appConfig.featureFlags.showBrowseV2"), "true");
    // A list of scalars reads better on one line than as an index per element.
    assertEquals(flat.get("appConfig.authConfig.allowedAccessTokenDurations"), "P1D, P30D");
  }

  @Test
  public void testDiffReportsChangedAndMissingKeys() throws Exception {
    Map<String, String> left =
        JsonFlattener.flatten(MAPPER.readTree("{\"a\": 1, \"b\": 2, \"onlyLeft\": 3}"));
    Map<String, String> right = JsonFlattener.flatten(MAPPER.readTree("{\"a\": 1, \"b\": 9}"));

    Map<String, String[]> differences = JsonFlattener.diff(left, right);

    assertEquals(differences.keySet(), java.util.Set.of("b", "onlyLeft"));
    assertEquals(differences.get("b"), new String[] {"2", "9"});
    assertNull(differences.get("onlyLeft")[1]);
  }
}
