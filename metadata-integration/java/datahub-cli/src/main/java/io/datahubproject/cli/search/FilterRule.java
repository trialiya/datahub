package io.datahubproject.cli.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One condition of a GraphQL {@code FacetFilterInput}.
 *
 * @param field the Elasticsearch field name, already mapped from the user-facing alias
 * @param condition a {@code FilterOperator} value
 */
public record FilterRule(String field, String condition, List<String> values, boolean negated) {

  public static FilterRule of(String field, String condition, List<String> values) {
    return new FilterRule(field, condition, values, false);
  }

  public FilterRule negate() {
    return new FilterRule(field, condition, values, !negated);
  }

  public Map<String, Object> toGraphQL() {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("field", field);
    rule.put("condition", condition);
    rule.put("values", values);
    if (negated) {
      rule.put("negated", true);
    }
    return rule;
  }
}
