package io.datahubproject.cli.search;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class EntityTypesTest {

  @Test
  public void testCamelCaseBecomesUpperUnderscore() {
    assertEquals(EntityTypes.toGraphQL("dataset"), "DATASET");
    assertEquals(EntityTypes.toGraphQL("glossaryTerm"), "GLOSSARY_TERM");
  }

  @Test
  public void testDataHubPrefixIsStripped() {
    assertEquals(EntityTypes.toGraphQL("dataHubPolicy"), "POLICY");
  }

  @Test
  public void testSpecialCases() {
    assertEquals(EntityTypes.toGraphQL("corpuser"), "CORP_USER");
    assertEquals(EntityTypes.toGraphQL("mlModelGroup"), "MLMODEL_GROUP");
  }

  @Test
  public void testEnumValuesPassThrough() {
    assertEquals(EntityTypes.toGraphQL("DATASET"), "DATASET");
  }
}
