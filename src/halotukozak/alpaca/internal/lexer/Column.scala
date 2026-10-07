package halotukozak
package alpaca
package internal
package lexer

/**
 * A context fragment that tracks the current 1-based column position within
 * the line, resetting to 1 on a newline.
 *
 * Use it as a field of a lexer context:
 * {{{
 * case class MyCtx(column: Column = Column.Start) extends LexerCtx
 * }}}
 *
 * [[Tracking.materialize]] finds the `given Tracking[Column]` below and applies
 * it to that field after every match, threading a functional `copy` -- so
 * `column` stays an immutable `val`. `Column <: Int`, so `ctx.column`
 * reads as a plain `Int` everywhere; assigning it inside a rule body
 * (`ctx.column = Column(...)`) is rewritten to a `copy` too.
 *
 * (Named `Column`, not `Position`, to avoid shadowing the unrelated source
 * `Position` type used throughout this library's own error reporting.)
 */
opaque type Column <: Int = Int

object Column:
  /** The column a fresh context starts on. */
  val Start: Column = 1

  def apply(n: Int): Column = n

  /** Resets to 1 on a newline, otherwise advances by the matched length. */
  given Tracking[Column] =
    case ("\n", _) => 1
    case (matched, column) => column + matched.length
