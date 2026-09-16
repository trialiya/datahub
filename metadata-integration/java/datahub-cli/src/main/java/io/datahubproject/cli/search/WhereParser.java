package io.datahubproject.cli.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses a subset of the Python CLI's {@code --where} syntax:
 *
 * <pre>
 *   expr      := condition (AND condition)*
 *   condition := [NOT] field op value
 *   op        := '=' | '!=' | IN '(' value (',' value)* ')' | IS [NOT] NULL
 * </pre>
 *
 * <p>OR and parentheses are deliberately left out: supporting them means normalising the whole
 * expression to disjunctive normal form, which GMS requires because {@code orFilters} is an OR of
 * ANDs. Within a condition, {@code IN} still gives OR over that field's values, which covers most
 * real queries. Conditions that compile to several OR branches (only {@code env} today) are merged
 * with the other conditions by a cartesian product, which keeps the result in DNF.
 */
public final class WhereParser {

  /** The parsed expression, ready to be sent as {@code orFilters}. */
  public record Filters(List<List<FilterRule>> orFilters, List<String> entityTypes) {}

  private WhereParser() {}

  public static Filters parse(String expression) {
    List<String> entityTypes = new ArrayList<>();
    // Starts as a single empty AND clause; each condition multiplies it by its own branches.
    List<List<FilterRule>> clauses = new ArrayList<>();
    clauses.add(List.of());

    for (String part : splitTopLevel(expression)) {
      Condition condition = parseCondition(part);

      if (FilterFields.ENTITY_TYPE.equals(FilterFields.canonicalName(condition.field()))) {
        if (condition.negated()) {
          throw new IllegalArgumentException("Negating entity_type is not supported");
        }
        condition.values().forEach(value -> entityTypes.add(EntityTypes.toGraphQL(value)));
        continue;
      }

      List<List<FilterRule>> branches =
          FilterFields.compile(
              condition.field(), condition.operator(), condition.values(), condition.negated());
      clauses = merge(clauses, branches);
    }

    return new Filters(clauses, entityTypes);
  }

  /** Cartesian product of the accumulated AND clauses with a condition's OR branches. */
  private static List<List<FilterRule>> merge(
      List<List<FilterRule>> clauses, List<List<FilterRule>> branches) {
    List<List<FilterRule>> merged = new ArrayList<>();
    for (List<FilterRule> clause : clauses) {
      for (List<FilterRule> branch : branches) {
        List<FilterRule> combined = new ArrayList<>(clause);
        combined.addAll(branch);
        merged.add(List.copyOf(combined));
      }
    }
    return merged;
  }

  private record Condition(String field, String operator, List<String> values, boolean negated) {}

  private static List<String> splitTopLevel(String expression) {
    List<String> parts = new ArrayList<>();
    for (String part : expression.split("(?i)\\s+AND\\s+")) {
      if (!part.isBlank()) {
        parts.add(part.trim());
      }
    }
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("Empty filter expression");
    }
    return parts;
  }

  private static Condition parseCondition(String text) {
    // Without this, "a = 1 OR b = 2" parses as the single value "1 OR b = 2": it matches nothing
    // and gives no hint why.
    if (text.toUpperCase(Locale.ROOT).matches(".*\\sOR\\s.*")) {
      throw new IllegalArgumentException(
          "OR is not supported; use IN (a, b) for several values of one field: " + text);
    }

    boolean negated = false;
    String rest = text;
    if (rest.toUpperCase(Locale.ROOT).startsWith("NOT ")) {
      negated = true;
      rest = rest.substring(4).trim();
    }
    if (rest.contains("(") && !rest.toUpperCase(Locale.ROOT).contains(" IN ")) {
      throw new IllegalArgumentException(
          "Parentheses and OR are not supported; use IN (a, b) for several values: " + text);
    }

    // IS NULL / IS NOT NULL -> EXISTS, negated for the NULL form.
    String upper = rest.toUpperCase(Locale.ROOT);
    int isIndex = upper.indexOf(" IS ");
    if (isIndex > 0) {
      String field = rest.substring(0, isIndex).trim();
      String tail = upper.substring(isIndex + 4).trim();
      if (tail.equals("NOT NULL")) {
        return new Condition(field, "EXISTS", List.of("true"), negated);
      }
      if (tail.equals("NULL")) {
        return new Condition(field, "EXISTS", List.of("true"), !negated);
      }
      throw new IllegalArgumentException("Expected IS NULL or IS NOT NULL in: " + text);
    }

    int inIndex = upper.indexOf(" IN ");
    if (inIndex > 0) {
      String field = rest.substring(0, inIndex).trim();
      String list = rest.substring(inIndex + 4).trim();
      if (!list.startsWith("(") || !list.endsWith(")")) {
        throw new IllegalArgumentException("Expected IN (value, value) in: " + text);
      }
      return new Condition(
          field, "EQUAL", splitValues(list.substring(1, list.length() - 1)), negated);
    }

    int notEquals = rest.indexOf("!=");
    if (notEquals > 0) {
      return new Condition(
          rest.substring(0, notEquals).trim(),
          "EQUAL",
          List.of(unquote(rest.substring(notEquals + 2).trim())),
          !negated);
    }

    int equals = rest.indexOf('=');
    if (equals > 0) {
      return new Condition(
          rest.substring(0, equals).trim(),
          "EQUAL",
          splitValues(rest.substring(equals + 1).trim()),
          negated);
    }

    throw new IllegalArgumentException("Cannot parse condition: " + text);
  }

  /** Commas separate alternatives for one field, matching the Python CLI's {@code a,b} form. */
  private static List<String> splitValues(String text) {
    List<String> values = new ArrayList<>();
    for (String value : text.split(",")) {
      String trimmed = unquote(value.trim());
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("No values given in: " + text);
    }
    return values;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
