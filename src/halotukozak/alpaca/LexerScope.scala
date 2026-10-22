package halotukozak
package alpaca

import scala.annotation.{implicitNotFound, publicInBinary}

// In its own file because an opaque type is transparent to the whole file that declares it: next to `Token` and `ctx`,
// `LexerScope` would be just the context.
/**
 * Evidence that code runs where `ctx` is available: a `lexer` rule, an [[ErrorHandling.forLexer]] handler or an
 * [[ErrorHandling.forParser]] handler. Its `Ctx` member is the context type.
 */
@implicitNotFound("`ctx` can only be used inside a lexer rule, `ErrorHandling.forLexer` or `ErrorHandling.forParser`")
opaque type CtxScope <: { type Ctx } = AnyRef { type Ctx }

object CtxScope:
  /** The scope of a context of type `Ctx0`. */
  type Of[Ctx0] = CtxScope { type Ctx = Ctx0 }

  // At runtime the scope is the context itself, so neither direction allocates.
  @publicInBinary private[alpaca] def refl[C](ctx: C): Of[C] = ctx.asInstanceOf[Of[C]]

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
