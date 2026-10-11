package halotukozak
package alpaca
package internal
package lexer

/**
 * A context fragment that tracks the current 1-based line number.
 *
 * Use it as a field of a lexer context:
 * {{{
 * case class MyCtx(line: Line = Line.Start) extends LexerCtx
 * }}}
 *
 * The lexer updates it after every match with the `given Tracking[Line]` below,
 * so the field can stay an immutable `val`. `Line <: Int`, so `ctx.line` reads
 * as a plain `Int` everywhere; a rule body can also assign it
 * (`ctx.line = Line(...)`).
 *
 * In a lexeme it is the line the token starts on, in a [[LexerError]] the line
 * the unmatched input starts on.
 */
opaque type Line <: Int = Int

object Line:
  /** The line number a fresh context starts on. */
  val Start: Line = 1

  /** The line number `n`, e.g. to assign in a rule body: `ctx.line = Line(1)`. */
  def apply(n: Int): Line = n

  /** Advances by the number of `\n`s in the match, so `\r\n` and newlines inside a longer match count too. */
  given Tracking[Line] = (matched, line) => line + matched.count(_ == '\n')
