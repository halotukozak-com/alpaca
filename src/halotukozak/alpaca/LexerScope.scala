package halotukozak
package alpaca

import scala.annotation.{implicitNotFound, publicInBinary}

// In its own file because an opaque type is transparent to the whole file that declares it: next to `Token` and `ctx`,
// `LexerScope` would be just the context.
/**
 * Evidence that code runs inside a `lexer` rule, where `ctx` and `Token[...]` are available. Only the `lexer` macro
 * creates one; its `Ctx` member is the lexer context type.
 */
@implicitNotFound("`ctx` and `Token` can only be used inside a lexer rule")
opaque type LexerScope <: { type Ctx <: LexerCtx } = LexerCtx & { type Ctx <: LexerCtx }

object LexerScope:
  /** The scope of a lexer whose context type is `Context`. */
  type Of[Context <: LexerCtx] = LexerScope { type Ctx = Context }

  // At runtime the scope is the context itself, so neither direction allocates.
  @publicInBinary private[alpaca] def refl[C <: LexerCtx](ctx: C): Of[C] = ctx.asInstanceOf[Of[C]]

  extension (scope: LexerScope) @publicInBinary private[alpaca] def ctx: scope.Ctx = scope.asInstanceOf[scope.Ctx]
