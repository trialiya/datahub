package io.datahubproject.cli.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flattens a JSON tree into dotted paths, so nested config can be listed, filtered and diffed as
 * plain key-value pairs.
 */
public final class JsonFlattener {

  private JsonFlattener() {}

  /** Flattens into sorted {@code section.field -> value} entries. Arrays become one line each. */
  public static Map<String, String> flatten(JsonNode root) {
    Map<String, String> flat = new TreeMap<>();
    walk("", root, flat);
    return flat;
  }

  private static void walk(String path, JsonNode node, Map<String, String> flat) {
    if (node == null || node.isMissingNode()) {
      return;
    }
    if (node.isObject()) {
      node.properties().forEach(entry -> walk(join(path, entry.getKey()), entry.getValue(), flat));
      return;
    }
    if (node.isArray()) {
      if (node.isEmpty()) {
        flat.put(path, "[]");
        return;
      }
      // Arrays of scalars read better on one line than as an index per element.
      if (isScalarArray(node)) {
        StringBuilder joined = new StringBuilder();
        node.forEach(
            element -> joined.append(joined.isEmpty() ? "" : ", ").append(element.asText()));
        flat.put(path, joined.toString());
        return;
      }
      for (int i = 0; i < node.size(); i++) {
        walk(path + "[" + i + "]", node.get(i), flat);
      }
      return;
    }
    flat.put(path, node.isNull() ? "" : node.asText());
  }

  private static boolean isScalarArray(JsonNode array) {
    for (JsonNode element : array) {
      if (element.isContainerNode()) {
        return false;
      }
    }
    return true;
  }

  private static String join(String path, String key) {
    return path.isEmpty() ? key : path + "." + key;
  }

  /**
   * Compares two flattened trees.
   *
   * @return every differing key, mapped to the pair of values; a missing side is null
   */
  public static Map<String, String[]> diff(Map<String, String> left, Map<String, String> right) {
    Map<String, String[]> differences = new LinkedHashMap<>();
    TreeMap<String, String> keys = new TreeMap<>(left);
    keys.putAll(right);
    for (String key : keys.keySet()) {
      String leftValue = left.get(key);
      String rightValue = right.get(key);
      if (leftValue == null || rightValue == null || !leftValue.equals(rightValue)) {
        differences.put(key, new String[] {leftValue, rightValue});
      }
    }
    return differences;
  }
}
