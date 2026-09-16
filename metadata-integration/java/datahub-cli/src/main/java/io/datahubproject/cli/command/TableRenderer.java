package io.datahubproject.cli.command;

import java.io.PrintWriter;
import java.util.List;

/** Renders rows as a fixed-width text table, sizing each column to its widest cell. */
public final class TableRenderer {

  private static final String COLUMN_SEPARATOR = "  ";

  private TableRenderer() {}

  public static void render(PrintWriter out, List<String> headers, List<List<String>> rows) {
    int[] widths = new int[headers.size()];
    for (int i = 0; i < headers.size(); i++) {
      widths[i] = headers.get(i).length();
    }
    for (List<String> row : rows) {
      for (int i = 0; i < row.size() && i < widths.length; i++) {
        widths[i] = Math.max(widths[i], row.get(i).length());
      }
    }

    out.println(formatRow(headers, widths));
    for (List<String> row : rows) {
      out.println(formatRow(row, widths));
    }
  }

  private static String formatRow(List<String> cells, int[] widths) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        line.append(COLUMN_SEPARATOR);
      }
      // The last column is not padded, so the output has no trailing whitespace.
      if (i == cells.size() - 1) {
        line.append(cells.get(i));
      } else {
        line.append(pad(cells.get(i), widths[i]));
      }
    }
    return line.toString();
  }

  private static String pad(String value, int width) {
    return value.length() >= width ? value : value + " ".repeat(width - value.length());
  }
}
