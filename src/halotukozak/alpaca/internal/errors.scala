package halotukozak
package alpaca.internal

import scala.quoted.*

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
 * Whether a macro expansion has reported any compile error, e.g. to skip side effects, such as the grammar export, that
 * need a valid definition. Create one per expansion, as a `given` for [[error]] and [[errorAndAbort]].
 */
private[internal] final class Diagnostics:
  private var reported = false

  def hasErrors: Boolean = reported

  private[internal] def markError(): Unit = reported = true

/**
 * Reports a compile error at `pos`.
 *
 * Use this instead of `quotes.reflect.report.error`: the position is mandatory, so an error can't silently land on
 * the macro expansion site. Pass `Position.ofMacroExpansion` explicitly when that really is the right place.
 */
private[internal] def error(using Quotes, Diagnostics)(message: Shown, pos: quotes.reflect.Position): Unit =
  summon[Diagnostics].markError()
  quotes.reflect.report.error(withLocation(message, pos), pos)
private[internal] def error(using Quotes, Diagnostics)(message: Shown, source: Source): Unit =
  val (located, pos) = locate(message, source)
  error(located, pos)

/**
 * Reports a compile error at `pos` and aborts the macro expansion.
 *
 * Use this instead of `quotes.reflect.report.errorAndAbort`: the position is mandatory, so an error can't silently
 * land on the macro expansion site. Pass `Position.ofMacroExpansion` explicitly when that really is the right place.
 */
private[internal] def errorAndAbort(using Quotes, Diagnostics)(message: Shown, pos: quotes.reflect.Position): Nothing =
  summon[Diagnostics].markError()
  quotes.reflect.report.errorAndAbort(withLocation(message, pos), pos)
private[internal] def errorAndAbort(using Quotes, Diagnostics)(message: Shown, source: Source): Nothing =
  val (located, pos) = locate(message, source)
  errorAndAbort(located, pos)

/**
 * Aborts the macro expansion if any error has been reported, without reporting another one: each error is already at
 * its own position.
 */
private[internal] def abortOnErrors()(using Diagnostics): Unit =
  if summon[Diagnostics].hasErrors then throw new scala.quoted.runtime.StopMacroExpansion

/**
 * Reports at `source` when it's in the macro expansion's file; otherwise, since its position can't be rebuilt there,
 * at the macro expansion, with the message naming where `source` is.
 */
private def locate(using quotes: Quotes)(message: Shown, source: Source): (Shown, quotes.reflect.Position) =
  source.toPosition match
    case Some(pos) => (message, pos)
    case None =>
      (show"$message\n(declared at ${source.file.showRaw}:${source.line + 1})", quotes.reflect.Position.ofMacroExpansion)

/**
 * `message`, naming where `pos` is when the compiler shows the error elsewhere.
 *
 * An error from a macro expansion is shown at the outermost inline call, unless that call encloses `pos`; the rest is
 * only in the "Inline stack trace", which IDEs and build servers drop. A parser's tables are expanded at its
 * `extends Parser`, so its errors at a production would show there with no hint of the production.
 */
private def withLocation(using quotes: Quotes)(message: Shown, pos: quotes.reflect.Position): Shown =
  val expansion = quotes.reflect.Position.ofMacroExpansion
  val enclosed =
    pos.sourceFile.path == expansion.sourceFile.path && expansion.start <= pos.start && pos.end <= expansion.end
  if enclosed then message
  else
    val code =
      pos.sourceCode.flatMap(_.linesIterator.nextOption()).fold("".showRaw)(code => show": ${code.trim.showRaw}")
    show"${message.stripLineEnd.showRaw}\n(at line ${pos.startLine + 1}$code)"

// $COVERAGE-ON$
