package io.datahubproject.cli.client;

import static org.testng.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.testng.annotations.Test;

public class DataHubHttpClientTest {

  @Test
  public void testUrnSyntaxSurvivesPathEncoding() {
    // GMS matches the URN in the path literally, so these characters must not be escaped.
    String urn = "urn:li:dataset:(urn:li:dataPlatform:hive,my_db.my_table,PROD)";

    assertEquals(DataHubHttpClient.encodePathSegment(urn), urn);
  }

  @Test
  public void testUnsafeCharactersAreEncoded() {
    assertEquals(
        DataHubHttpClient.encodePathSegment("urn:li:corpuser:a b"), "urn:li:corpuser:a%20b");
    assertEquals(DataHubHttpClient.encodePathSegment("a?b#c"), "a%3Fb%23c");
  }

  @Test
  public void testWithQuery() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("version", "2");
    params.put("systemMetadata", "true");

    assertEquals(
        DataHubHttpClient.withQuery("/path", params), "/path?version=2&systemMetadata=true");
    assertEquals(DataHubHttpClient.withQuery("/path", Map.of()), "/path");
  }
}
