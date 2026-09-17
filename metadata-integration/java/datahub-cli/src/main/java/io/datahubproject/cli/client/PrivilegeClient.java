package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.datahubproject.cli.search.EntityTypes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Evaluates what an actor may do, via the GraphQL {@code getGrantedPrivileges} query. */
public class PrivilegeClient {

  private static final String QUERY =
      """
      query getGrantedPrivileges($input: GetGrantedPrivilegesInput!) {
        getGrantedPrivileges(input: $input) {
          privileges
          evaluationDetails { policyName reason }
        }
      }
      """;

  /** A policy that was evaluated and did not grant anything, with the reason it gave. */
  public record Denial(String policyName, String reason) {}

  /**
   * @param privileges what the actor is granted, sorted
   * @param denials per-policy reasons, or null when they were not requested or not permitted
   */
  public record Grant(List<String> privileges, List<Denial> denials) {}

  private final GraphQLClient graphQLClient;

  public PrivilegeClient(GraphQLClient graphQLClient) {
    this.graphQLClient = graphQLClient;
  }

  /**
   * @param resourceUrn the resource to evaluate against, or null for the actor's platform-wide
   *     privileges
   * @param withDetails ask for the per-policy reasons; the server only fills them in for callers
   *     that can manage policies
   */
  public Grant grantedPrivileges(String actorUrn, String resourceUrn, boolean withDetails)
      throws IOException {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("actorUrn", actorUrn);
    input.put("includeEvaluationDetails", withDetails);
    if (resourceUrn != null) {
      Map<String, Object> resourceSpec = new LinkedHashMap<>();
      resourceSpec.put("resourceType", EntityTypes.toGraphQL(Urns.entityType(resourceUrn)));
      resourceSpec.put("resourceUrn", resourceUrn);
      input.put("resourceSpec", resourceSpec);
    }

    JsonNode result =
        graphQLClient.query(QUERY, Map.of("input", input)).path("getGrantedPrivileges");

    TreeSet<String> privileges = new TreeSet<>();
    result.path("privileges").forEach(privilege -> privileges.add(privilege.asText()));

    JsonNode details = result.path("evaluationDetails");
    List<Denial> denials = null;
    if (details.isArray()) {
      denials = new ArrayList<>();
      for (JsonNode detail : details) {
        denials.add(new Denial(detail.path("policyName").asText(), detail.path("reason").asText()));
      }
    }
    return new Grant(List.copyOf(privileges), denials);
  }
}
