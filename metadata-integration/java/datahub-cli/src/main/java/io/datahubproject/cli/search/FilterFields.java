package io.datahubproject.cli.search;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the user-facing filter aliases onto Elasticsearch fields and coerces their values, mirroring
 * the Python SDK's filter DSL so both CLIs accept the same vocabulary.
 *
 * <p>A single alias can compile to several OR branches; {@code env} is the one that does, because
 * most entities carry it in {@code origin} while containers carry it in {@code env}.
 */
public final class FilterFields {

  /** Alias for the entity type, which GMS takes as a separate argument rather than as a filter. */
  public static final String ENTITY_TYPE = "entity_type";

  private static final Map<String, String> ALIASES =
      Map.of(
          "type", ENTITY_TYPE,
          "environment", "env",
          "subtype", "entity_subtype",
          "term", "glossary_term");

  private static final String PLATFORM_URN_PREFIX = "urn:li:dataPlatform:";

  private FilterFields() {}

  public static String canonicalName(String field) {
    String lowered = field.toLowerCase(Locale.ROOT);
    return ALIASES.getOrDefault(lowered, lowered);
  }

  /**
   * Compiles one condition into OR branches, each of which is a list of ANDed rules.
   *
   * @param condition a {@code FilterOperator} value
   */
  public static List<List<FilterRule>> compile(
      String field, String condition, List<String> values, boolean negated) {
    String name = canonicalName(field);

    // EXISTS carries a marker value rather than a value of the field, so the per-field value
    // coercion and URN validation below must not run for it.
    if ("EXISTS".equals(condition)) {
      FilterRule rule = FilterRule.of(elasticFieldName(name, field), condition, values);
      return List.of(List.of(negated ? rule.negate() : rule));
    }

    List<List<FilterRule>> branches =
        switch (name) {
          case "platform" ->
              single(FilterRule.of("platform.keyword", condition, toPlatformUrns(values)));
          case "env" ->
              // Containers keep the environment in "env", everything else in "origin".
              List.of(
                  List.of(FilterRule.of("origin", condition, values)),
                  List.of(FilterRule.of("env", condition, values)));
          case "entity_subtype" -> single(FilterRule.of("typeNames", condition, values));
          case "domain" -> single(FilterRule.of("domains", condition, requireUrns(name, values)));
          case "container" ->
              single(FilterRule.of("container", condition, requireUrns(name, values)));
          case "tag" -> single(FilterRule.of("tags", condition, requireUrns(name, values)));
          case "glossary_term" ->
              single(FilterRule.of("glossaryTerms", condition, requireUrns(name, values)));
          case "owner" -> single(FilterRule.of("owners", condition, requireUrns(name, values)));
          // Anything else is passed through as a raw Elasticsearch field name.
          default -> single(FilterRule.of(field, condition, values));
        };

    if (!negated) {
      return branches;
    }
    // A negated multi-branch condition would need De Morgan across branches; only "env" is
    // multi-branch, and negating it is not something the subset supports.
    if (branches.size() > 1) {
      throw new IllegalArgumentException("Cannot negate the '" + name + "' filter");
    }
    return List.of(branches.get(0).stream().map(FilterRule::negate).toList());
  }

  /** The Elasticsearch field for an alias, without any value coercion. */
  private static String elasticFieldName(String canonicalName, String originalField) {
    return switch (canonicalName) {
      case "platform" -> "platform.keyword";
      // "env" is multi-field; for an existence check "origin" is the one that matters.
      case "env" -> "origin";
      case "entity_subtype" -> "typeNames";
      case "domain" -> "domains";
      case "tag" -> "tags";
      case "glossary_term" -> "glossaryTerms";
      case "owner" -> "owners";
      default -> originalField;
    };
  }

  private static List<List<FilterRule>> single(FilterRule rule) {
    return List.of(List.of(rule));
  }

  /** Accepts a platform name or a full URN, as the Python DSL does. */
  private static List<String> toPlatformUrns(List<String> values) {
    return values.stream()
        .map(value -> value.startsWith("urn:li:") ? value : PLATFORM_URN_PREFIX + value)
        .toList();
  }

  private static List<String> requireUrns(String field, List<String> values) {
    values.forEach(
        value -> {
          if (!value.startsWith("urn:li:")) {
            throw new IllegalArgumentException(
                "The '" + field + "' filter needs a full URN, got: " + value);
          }
        });
    return values;
  }
}
