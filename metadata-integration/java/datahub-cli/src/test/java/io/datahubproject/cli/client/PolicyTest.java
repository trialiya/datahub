package io.datahubproject.cli.client;

import static org.testng.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.testng.annotations.Test;

public class PolicyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void testFromJsonReadsPrivilegesAndActors() throws Exception {
    String json =
        """
        {
          "urn": "urn:li:dataHubPolicy:test",
          "name": "Test Policy",
          "type": "PLATFORM",
          "state": "ACTIVE",
          "description": "",
          "privileges": ["MANAGE_POLICIES", "MANAGE_DOMAINS"],
          "actors": {
            "users": ["urn:li:corpuser:alice"],
            "groups": [],
            "roles": ["urn:li:dataHubRole:Admin"],
            "allUsers": false,
            "allGroups": false,
            "resourceOwners": true
          }
        }
        """;

    Policy policy = Policy.fromJson(MAPPER.readTree(json));

    assertEquals(policy.name(), "Test Policy");
    assertEquals(policy.privileges(), List.of("MANAGE_POLICIES", "MANAGE_DOMAINS"));
    assertEquals(policy.actors().users(), List.of("urn:li:corpuser:alice"));
    assertEquals(policy.actors().summarize(), "<resourceOwners>, users: 1, roles: 1");
  }

  @Test
  public void testSummarizeDynamicActorRules() {
    Policy.Actors actors = new Policy.Actors(List.of(), List.of(), List.of(), true, false, false);

    assertEquals(actors.summarize(), "<allUsers>");
  }

  @Test
  public void testSummarizeWithNoActors() {
    Policy.Actors actors = new Policy.Actors(List.of(), List.of(), List.of(), false, false, false);

    assertEquals(actors.summarize(), "-");
  }
}
