package halotukozak
package alpaca

import scala.annotation.{implicitNotFound, publicInBinary}

// In its own file because an opaque type is transparent to the whole file that declares it: next to `Token` and `ctx`,
// `LexerScope` would be just the context.
/**
 * Evidence that code runs where `ctx` is available: a `lexer` rule, a parser action, or an [[ErrorHandling.forLexer]]
 * or [[ErrorHandling.forParser]] handler. Its `Ctx` member is the context type.
 */
@implicitNotFound(
  "`ctx` can only be used inside a lexer rule, a parser action, `ErrorHandling.forLexer` or `ErrorHandling.forParser`",
)
opaque type CtxScope <: { type Ctx <: AlpacaCtx } = AlpacaCtx & { type Ctx <: AlpacaCtx }

object CtxScope:
  /** The scope of a context of type `Ctx0`. */
  type Of[Ctx0 <: AlpacaCtx] = CtxScope { type Ctx = Ctx0 }

  // At runtime the scope is the context itself, so neither direction allocates.
  @publicInBinary private[alpaca] def refl[C <: AlpacaCtx](ctx: C): Of[C] = ctx.asInstanceOf[Of[C]]

  extension (scope: CtxScope) @publicInBinary private[alpaca] def ctx: scope.Ctx = scope.asInstanceOf[scope.Ctx]

/**
 * Evidence that code runs inside a `lexer` rule or an [[ErrorHandling.forLexer]] handler, where `ctx` and `Token[...]`
 * are available. Only the `lexer` macro and `ErrorHandling.forLexer` create one; its `Ctx` member is the lexer context
 * type.
 */
@implicitNotFound("`ctx` and `Token` can only be used inside a lexer rule")
opaque type LexerScope <: CtxScope & { type Ctx <: LexerCtx } = LexerCtx & { type Ctx <: LexerCtx }

object LexerScope:
  /** The scope of a lexer whose context type is `Ctx0`. */
  type Of[Ctx0 <: LexerCtx] = LexerScope { type Ctx = Ctx0 }

  @publicInBinary private[alpaca] def refl[C <: LexerCtx](ctx: C): Of[C] = ctx.asInstanceOf[Of[C]]

/**
 * Evidence that code runs inside a production of a parser's `rule`, where `ctx` and the token and rule extractors
 * (`MyLexer.NUM(n)`, `Expr(e)`, `Expr.List(es)`) are available. Only `rule` binds one; its `Ctx` member is the parser
 * context type.
 */
@implicitNotFound("Token and rule extractors can only be used in a production of a `rule`")
opaque type RuleScope <: CtxScope & { type Ctx <: ParserCtx } = ParserCtx & { type Ctx <: ParserCtx }

object RuleScope:
  /** The scope of a production of a parser whose context type is `Ctx0`. */
  type Of[Ctx0 <: ParserCtx] = RuleScope { type Ctx = Ctx0 }

  @publicInBinary private[alpaca] def refl[C <: ParserCtx](ctx: C): Of[C] = ctx.asInstanceOf[Of[C]]
