package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fetches policies via the GraphQL {@code listPolicies} query. */
public class PolicyClient {

  private static final String LIST_POLICIES_QUERY =
      """
      query listPolicies($input: ListPoliciesInput!) {
        listPolicies(input: $input) {
          start
          count
          total
          policies {
            urn
            name
            type
            state
            description
            privileges
            actors {
              users
              groups
              roles
              allUsers
              allGroups
              resourceOwners
            }
          }
        }
      }
      """;

  /** GMS caps a single page; paging keeps the request well inside that limit. */
  private static final int PAGE_SIZE = 100;

  private final GraphQLClient graphQLClient;

  public PolicyClient(GraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  /**
   * Returns every policy the caller is allowed to see, paging until the result set is exhausted.
   */
  public List<Policy> listPolicies() throws IOException {
    List<Policy> policies = new ArrayList<>();
    int start = 0;
    int total;

    do {
      Map<String, Object> input = new LinkedHashMap<>();
      input.put("start", start);
      input.put("count", PAGE_SIZE);

      JsonNode result =
          graphQLClient.query(LIST_POLICIES_QUERY, Map.of("input", input)).path("listPolicies");
      total = result.path("total").asInt(0);

      JsonNode page = result.path("policies");
      if (!page.isArray() || page.isEmpty()) {
        break;
      }
      page.forEach(node -> policies.add(Policy.fromJson(node)));
      start += page.size();
    } while (policies.size() < total);

    return policies;
  }
}
