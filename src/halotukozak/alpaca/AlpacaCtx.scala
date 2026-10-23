package halotukozak
package alpaca

/**
 * A lexer or a parser context, [[LexerCtx]] or [[ParserCtx]]: a case class whose fields all have default values, read
 * and assigned as `ctx` in lexer rules, parser actions and the handlers built by [[ErrorHandling.forLexer]].
 *
 * There `ctx.count += 1` is rewritten into a `copy`, so the fields can stay `val`s: `ctx` has a setter for each one,
 * which resolves through `Selectable` to `applyDynamic` and is rewritten away before it is ever called.
 */
type AlpacaCtx = LexerCtx | ParserCtx
