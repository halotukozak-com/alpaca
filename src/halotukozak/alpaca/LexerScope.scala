package halotukozak
package alpaca

import scala.annotation.{implicitNotFound, publicInBinary}

// In its own file because an opaque type is transparent to the whole file that declares it: next to `Token` and `ctx`,
// `LexerScope[C]` would be just `C`.
/**
 * Evidence that code runs inside a `lexer` rule, where `ctx` and `Token[...]` are available. Only the `lexer` macro
 * creates one.
 *
 * @tparam C the lexer context type
 */
@implicitNotFound("`ctx` and `Token` can only be used inside a lexer rule")
opaque type LexerScope[+C <: LexerCtx] = C

object LexerScope:
  // At runtime the scope is the context itself, so neither direction allocates.
  @publicInBinary private[alpaca] def refl[C <: LexerCtx](ctx: C): LexerScope[C] = ctx

  extension [C <: LexerCtx](scope: LexerScope[C]) @publicInBinary private[alpaca] def ctx: C = scope
