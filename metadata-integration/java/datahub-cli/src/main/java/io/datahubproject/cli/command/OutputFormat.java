package io.datahubproject.cli.command;

/** Output shapes shared by the read commands; CSV and JSON exist so results can be diffed. */
public enum OutputFormat {
  TABLE,
  CSV,
  JSON
}
