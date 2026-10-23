package halotukozak
package alpaca

import scala.annotation.{implicitNotFound, publicInBinary}

// The scopes have a file of their own because an opaque type is transparent to the whole file that declares it: next to
// `Token`, `ctx` or `rule`, a scope would be just what it hides. `LexerScope` and `ProductionScope` must share it with
// `CtxScope` anyway: they are declared as `CtxScope`s, which only holds where `CtxScope` is transparent.
/**
 * Evidence that code runs where `ctx` is available: a `lexer` rule, a parser action, or an [[ErrorHandling.forLexer]]
 * handler. Its `Ctx` member is the context type.
 */
@implicitNotFound("`ctx` can only be used inside a lexer rule, a parser action or `ErrorHandling.forLexer`")
opaque type CtxScope <: { type Ctx <: AlpacaCtx } = AlpacaCtx & { type Ctx <: AlpacaCtx }

object CtxScope:
  /** The scope of a context of type `Ctx0`. */
  type Of[Ctx0 <: AlpacaCtx] = CtxScope { type Ctx = Ctx0 }

  // At runtime the scope is the context itself: the macros cast the context to it, and this casts it back.
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

/**
 * Evidence that code runs inside a production of a parser's `rule`, where `ctx` and the token and rule extractors
 * (`MyLexer.NUM(n)`, `Expr(e)`, `Expr.List(es)`) are available. Only `rule` binds one; its `Ctx` member is the parser
 * context type.
 */
@implicitNotFound("Token and rule extractors can only be used in a production of a `rule`")
opaque type ProductionScope <: CtxScope & { type Ctx <: ParserCtx } = ParserCtx & { type Ctx <: ParserCtx }

object ProductionScope:
  /** The scope of a production of a parser whose context type is `Ctx0`. */
  type Of[Ctx0 <: ParserCtx] = ProductionScope { type Ctx = Ctx0 }

/**
 * Evidence that code runs inside [[resolutions]], where `production`, `Production(...)` and `before`/`after` are
 * available. Gives `production` the parser it refers to.
 *
 * @tparam ParserType the parser's singleton type
 */
@implicitNotFound("`production`, `Production(...)`, `before` and `after` can only be used inside resolutions(...)")
opaque type ResolutionScope[ParserType <: internal.parser.Parser[?]] = Unit
object ResolutionScope:
  private[alpaca] def refl[ParserType <: internal.parser.Parser[?]]: ResolutionScope[ParserType] = ()

/**
 * Evidence that code runs inside a parser definition, where `rule` and named productions are available. Every
 * [[Parser]] provides one; its `Ctx` member is the parser context type, which the productions of a `rule` bind as
 * their [[ProductionScope]].
 */
@implicitNotFound("`rule` and named productions can only be used inside a parser definition")
opaque type ParserScope <: { type Ctx <: ParserCtx } = AnyRef & { type Ctx <: ParserCtx }
object ParserScope:
  /** The scope of a parser whose context type is `Ctx0`. */
  type Of[Ctx0 <: ParserCtx] = ParserScope { type Ctx = Ctx0 }

  private[alpaca] def refl[Ctx <: ParserCtx]: Of[Ctx] = null.asInstanceOf[Of[Ctx]]
