package halotukozak
package alpaca

/**
 * The base of every lexer and parser context, [[LexerCtx]] and [[ParserCtx]]: a case class whose fields all have
 * default values, read and assigned as `ctx` in lexer rules, parser actions and the handlers built by
 * [[ErrorHandling.forLexer]].
 *
 * There `ctx.count += 1` is rewritten into a `copy`, so the fields can stay `val`s: `ctx` has a setter for each one,
 * which resolves through `Selectable` to `applyDynamic` and is rewritten away before it is ever called.
 */
// `transparent`, like `Product`: an inferred type, such as that of `if ... then lexerCtx else parserCtx`, doesn't widen to it
transparent trait AlpacaCtx extends Product, Selectable
