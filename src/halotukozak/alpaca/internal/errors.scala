package halotukozak
package alpaca.internal

// $COVERAGE-OFF$

/**
 * Error message for methods that should only be called during parser definition.
 *
 * This constant is used in @compileTimeOnly annotations to indicate that a method
 * was called outside of its intended context. It appears in compile errors when
 * parser-related methods are called outside the parser definition scope.
 */
private[alpaca] final val ParserOnly = "Should never be called outside the parser definition"

/**
 * Error message for methods that should only be called during rule definition.
 *
 * This constant is used in @compileTimeOnly annotations to indicate that a method
 * was called outside of its intended context. It appears in compile errors when
 * rule-related methods are called outside the rule definition scope.
 */
private[alpaca] final val RuleOnly = "Should never be called outside the rule definition"

/**
 * Error message for methods that should only be called during conflict resolution.
 *
 * This constant is used in @compileTimeOnly annotations to indicate that a method
 * was called outside of its intended context. It appears in compile errors when
 * conflict resolution methods are called outside the conflict resolution definition scope.
 */
private[alpaca] final val ConflictResolutionOnly = "Should never be called outside the conflict resolution definition"

/**
 * Reports a macro expansion's compile errors and remembers whether any were reported.
 *
 * Use this instead of `quotes.reflect.report`: the position is mandatory, so an error can't silently land on the macro
 * expansion site. Pass `Position.ofMacroExpansion` explicitly when that really is the right place. Create one per
 * expansion, as a `given` for the code it calls.
 */
private[internal] final class Diagnostics:
  private var reported = false

  /** Reports a compile error at `pos` and lets the expansion go on, so later checks can still report theirs. */
  def error(using quotes: Quotes)(message: Shown, pos: quotes.reflect.Position): Unit =
    reported = true
    quotes.reflect.report.error(message, pos)

  /** Reports a compile error at `source` and lets the expansion go on, so later checks can still report theirs. */
  def error(using quotes: Quotes)(message: Shown, source: Source): Unit =
    val (located, pos) = locate(message, source)
    error(located, pos)

  /** Reports a compile error at `pos` and aborts the expansion. */
  def abort(using quotes: Quotes)(message: Shown, pos: quotes.reflect.Position): Nothing =
    reported = true
    quotes.reflect.report.errorAndAbort(message, pos)

  /** Reports a compile error at `source` and aborts the expansion. */
  def abort(using quotes: Quotes)(message: Shown, source: Source): Nothing =
    val (located, pos) = locate(message, source)
    abort(located, pos)

  /** Whether any error has been reported, e.g. to skip side effects, such as the grammar export, that need a valid definition. */
  def hasErrors: Boolean = reported

/**
 * Reports at `source` when it's in the macro expansion's file; otherwise, since its position can't be rebuilt there,
 * at the macro expansion, with the message naming where `source` is.
 */
private def locate(using quotes: Quotes)(message: Shown, source: Source): (Shown, quotes.reflect.Position) =
  source.toPosition match
    case Some(pos) => (message, pos)
    case None =>
      (show"$message\n(declared at ${source.file.showRaw}:${source.line + 1})", quotes.reflect.Position.ofMacroExpansion)

// $COVERAGE-ON$
