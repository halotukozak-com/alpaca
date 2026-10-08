package halotukozak
package alpaca
package internal
package lexer

/**
 * A context fragment that tracks the current 1-based column within the line,
 * counted in Unicode code points (an emoji is one column), resetting to 1 on a
 * newline.
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
 * In a lexeme it is the column the token starts at, in a [[LexerError]] the
 * column the unmatched input starts at.
 *
 * (Named `Column`, not `Position`, to avoid shadowing the unrelated source
 * `Position` type used throughout this library's own error reporting.)
 */
opaque type Column <: Int = Int

object Column:
  /** The column a fresh context starts on. */
  val Start: Column = 1

  def apply(n: Int): Column = n

  /** Resets to 1 on a newline, otherwise advances by the number of code points matched. */
  given Tracking[Column] =
    case ("\n", _) => 1
    case (matched, column) => column + matched.codePointCount(0, matched.length)
