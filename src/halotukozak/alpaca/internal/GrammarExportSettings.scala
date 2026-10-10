package halotukozak
package alpaca.internal

/**
 * Configuration for exporting a lexer's and a parser's grammar as JSON during
 * compilation, for external tooling (e.g. an IDE plugin) that needs a grammar's
 * rules without running the compiled lexer/parser. Read from the
 * `ALPACA_GRAMMAR_EXPORT_DIR` environment variable.
 *
 * @param exportDirectory directory to write the `<name>.tokens.json`, `<name>.productions.json` and
 *   `<name>.table.json` files into; `None` writes nothing
 */
// $COVERAGE-OFF$
private[internal] final case class GrammarExportSettings(exportDirectory: Option[String])

private[internal] object GrammarExportSettings:
  private final val DirectoryEnvVar = "ALPACA_GRAMMAR_EXPORT_DIR"

  // Read from an env var for the same reason as DebugSettings: -Xmacro-settings/
  // CompilationInfo.XmacroSettings is @experimental, and being @experimental is
  // contagious to every caller of the lexer/parser macros.
  given GrammarExportSettings = GrammarExportSettings(
    exportDirectory = sys.env.get(DirectoryEnvVar),
  )
// $COVERAGE-ON$
