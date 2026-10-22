package halotukozak
package alpaca

import scala.annotation.{compileTimeOnly, unused}

/**
 * The base of every lexer and parser context, [[LexerCtx]] and [[ParserCtx]]: a case class whose fields all have
 * default values, read and assigned as `ctx` in lexer rules, parser actions and the handlers built by
 * [[ErrorHandling.forLexer]] and [[ErrorHandling.forParser]].
 *
 * There `ctx.count += 1` is rewritten into a `copy`, so the fields can stay `val`s: `ctx` has a setter for each one,
 * which resolves through `Selectable` to [[applyDynamic]] and is rewritten away before it is ever called.
 */
// `transparent`, like `Product`: an inferred type, such as that of `if ... then lexerCtx else parserCtx`, doesn't widen to it
transparent trait AlpacaCtx extends Product, Selectable:
  // `@compileTimeOnly`, not an inline `compiletime.error`: a parser's `rule` drops its productions only after
  // inlining what they call, so the error would fire in every action
  /** Not meant to be called: assigning a context field elsewhere is a compile error. */
  @compileTimeOnly("Context fields can only be assigned inside a lexer rule, a parser action or an error handler")
  def applyDynamic(@unused name: String)(@unused args: Any*): Any = throw UnsupportedOperationException(name)
