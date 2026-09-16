package io.datahubproject.cli.client;

/** Minimal URN handling: the CLI only needs the entity type out of a URN string. */
public final class Urns {

  private static final String PREFIX = "urn:li:";

  private Urns() {}

  /**
   * Extracts the entity type from a URN, e.g. {@code dataset} from {@code
   * urn:li:dataset:(urn:li:dataPlatform:hive,db.table,PROD)}.
   *
   * @throws IllegalArgumentException when the string is not a URN
   */
  public static String entityType(String urn) {
    if (urn == null || !urn.startsWith(PREFIX)) {
      throw new IllegalArgumentException("Not a DataHub URN: " + urn);
    }
    int typeEnd = urn.indexOf(':', PREFIX.length());
    if (typeEnd < 0 || typeEnd == PREFIX.length()) {
      throw new IllegalArgumentException("URN has no entity type: " + urn);
    }
    return urn.substring(PREFIX.length(), typeEnd);
  }
}
