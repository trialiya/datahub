package io.datahubproject.cli.search;

import java.util.Locale;
import java.util.Map;

/** Converts entity type names to the GraphQL {@code EntityType} enum. */
public final class EntityTypes {

  // Names whose enum value does not follow the camelCase -> UPPER_UNDERSCORE rule.
  private static final Map<String, String> SPECIAL_CASES =
      Map.of(
          "corpuser", "CORP_USER",
          "mlModel", "MLMODEL",
          "mlModelGroup", "MLMODEL_GROUP",
          "mlFeatureTable", "MLFEATURE_TABLE",
          "mlFeature", "MLFEATURE",
          "mlPrimaryKey", "MLPRIMARY_KEY");

  private static final String DATA_HUB_PREFIX = "DATA_HUB_";

  private EntityTypes() {}

  public static String toGraphQL(String entityType) {
    // An all-caps name is already an enum value.
    if (entityType.equals(entityType.toUpperCase(Locale.ROOT))) {
      return entityType;
    }
    String special = SPECIAL_CASES.get(entityType);
    if (special != null) {
      return special;
    }

    StringBuilder converted = new StringBuilder();
    for (char c : entityType.toCharArray()) {
      if (Character.isUpperCase(c)) {
        converted.append('_');
      }
      converted.append(Character.toUpperCase(c));
    }
    String result = converted.toString();
    if (result.startsWith("_")) {
      result = result.substring(1);
    }
    // dataHubPolicy -> DATA_HUB_POLICY -> POLICY
    return result.startsWith(DATA_HUB_PREFIX) ? result.substring(DATA_HUB_PREFIX.length()) : result;
  }
}
