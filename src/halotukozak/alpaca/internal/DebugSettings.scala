package halotukozak
package alpaca.internal

/**
 * Where the `parser` macro writes its debug files (productions, parse and action tables, conflict resolutions)
 * during compilation, read from the `ALPACA_DEBUG_DIR` environment variable.
 *
 * @param debugDirectory directory for debug output files; `None` writes nothing
 */
private[internal] final case class DebugSettings(
  debugDirectory: Option[String],
)

private[internal] object DebugSettings:
  private final val DirectoryEnvVar = "ALPACA_DEBUG_DIR"

  val default: DebugSettings = DebugSettings(
    debugDirectory = None,
  )

  // $COVERAGE-OFF$
  // Read from an env var rather than -Xmacro-settings/CompilationInfo.XmacroSettings:
  // that API is @experimental, and being @experimental is contagious to every caller of
  // the lexer/parser macros -- forcing consumers of this library onto -experimental too.
  given DebugSettings = DebugSettings(
    debugDirectory = sys.env.get(DirectoryEnvVar),
  )
// $COVERAGE-ON$
