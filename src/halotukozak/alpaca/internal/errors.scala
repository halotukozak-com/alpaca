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
 * Reports a compile error at `pos`.
 *
 * Use this instead of `quotes.reflect.report.error`: the position is mandatory, so an error can't silently land on
 * the macro expansion site. Pass `Position.ofMacroExpansion` explicitly when that really is the right place.
 */
private[internal] def error(using quotes: Quotes)(message: Shown, pos: quotes.reflect.Position): Unit =
  quotes.reflect.report.error(message, pos)

/**
 * Reports a compile error at `pos` and aborts the macro expansion.
 *
 * Use this instead of `quotes.reflect.report.errorAndAbort`: the position is mandatory, so an error can't silently
 * land on the macro expansion site. Pass `Position.ofMacroExpansion` explicitly when that really is the right place.
 */
private[internal] def errorAndAbort(using quotes: Quotes)(message: Shown, pos: quotes.reflect.Position): Nothing =
  quotes.reflect.report.errorAndAbort(message, pos)

// $COVERAGE-ON$
