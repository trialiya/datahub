package io.datahubproject.cli.search;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;
import org.testng.annotations.Test;

public class WhereParserTest {

  private static List<FilterRule> onlyClause(String expression) {
    WhereParser.Filters filters = WhereParser.parse(expression);
    assertEquals(filters.orFilters().size(), 1, filters.orFilters().toString());
    return filters.orFilters().get(0);
  }

  @Test
  public void testEntityTypeBecomesATypeArgumentRatherThanAFilter() {
    WhereParser.Filters filters = WhereParser.parse("entity_type = dataset AND platform = hive");

    assertEquals(filters.entityTypes(), List.of("DATASET"));
    assertEquals(filters.orFilters().get(0).size(), 1);
  }

  @Test
  public void testPlatformNameIsCoercedToAUrn() {
    FilterRule rule = onlyClause("platform = snowflake").get(0);

    assertEquals(rule.field(), "platform.keyword");
    assertEquals(rule.values(), List.of("urn:li:dataPlatform:snowflake"));
  }

  @Test
  public void testInListBecomesSeveralValuesOfOneRule() {
    FilterRule rule = onlyClause("platform IN (hive, snowflake)").get(0);

    assertEquals(
        rule.values(), List.of("urn:li:dataPlatform:hive", "urn:li:dataPlatform:snowflake"));
  }

  @Test
  public void testEnvExpandsToTwoOrBranchesCarryingTheOtherConditions() {
    // Containers keep the environment in "env", everything else in "origin", so the expression
    // has to reach GMS as two OR clauses, each still constrained by the platform condition.
    WhereParser.Filters filters = WhereParser.parse("platform = hive AND env = PROD");

    assertEquals(filters.orFilters().size(), 2);
    assertEquals(filters.orFilters().get(0).get(1).field(), "origin");
    assertEquals(filters.orFilters().get(1).get(1).field(), "env");
    filters.orFilters().forEach(clause -> assertEquals(clause.get(0).field(), "platform.keyword"));
  }

  @Test
  public void testNotAndNotEqualsNegateTheRule() {
    assertTrue(onlyClause("NOT platform = looker").get(0).negated());
    assertTrue(onlyClause("tag != urn:li:tag:PII").get(0).negated());
  }

  @Test
  public void testExistenceChecks() {
    FilterRule present = onlyClause("owner IS NOT NULL").get(0);
    assertEquals(present.field(), "owners");
    assertEquals(present.condition(), "EXISTS");
    assertTrue(!present.negated());

    assertTrue(onlyClause("owner IS NULL").get(0).negated());
  }

  @Test
  public void testOrIsRejectedRatherThanSwallowedIntoAValue() {
    // Parsing this as the value "hive OR platform = snowflake" would match nothing silently.
    assertThrows(
        IllegalArgumentException.class,
        () -> WhereParser.parse("platform = hive OR platform = snowflake"));
    assertThrows(IllegalArgumentException.class, () -> WhereParser.parse("(platform = hive)"));
  }

  @Test
  public void testUrnOnlyFieldsRejectBareNames() {
    assertThrows(IllegalArgumentException.class, () -> WhereParser.parse("tag = PII"));
  }
}
