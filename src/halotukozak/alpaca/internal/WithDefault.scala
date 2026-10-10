package halotukozak
package alpaca
package internal

import scala.annotation.publicInBinary

/**
 * Emulates a default type argument, which Scala 3 doesn't have.
 *
 * `lexer` and `Parser` take a `using Ctx withDefault D` clause, so `lexer { ... }` and `extends Parser` without a type
 * argument infer `Ctx = D`, while an explicit `[MyCtx]` keeps `MyCtx`. The given is always found by the compiler;
 * this is an implementation detail and never needs to be written or provided by users.
 *
 * @tparam Provided the provided type
 * @tparam Fallback the default type
 */
infix final class withDefault[Provided, Fallback] @publicInBinary private[alpaca] ()

private[alpaca] trait withDefaultLowImplicitPriority:

  /**
   * Ignore default - use the provided type when explicitly specified.
   *
   * @tparam Provided the type that was explicitly provided
   * @tparam Fallback the default type (ignored)
   */
  inline given useProvided[Provided, Fallback]: (Provided withDefault Fallback) = new (Provided withDefault Fallback)

object withDefault extends withDefaultLowImplicitPriority:

  /**
   * Infer type argument to default when no type is explicitly provided.
   *
   * @tparam Fallback the default type to use
   */
  inline given useDefault[Fallback]: (Fallback withDefault Fallback) = new (Fallback withDefault Fallback)
