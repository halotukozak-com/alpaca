package halotukozak
package alpaca.internal

/**
 * Type alias for valid token names.
 *
 * Token names must be singleton strings (string literals) to enable
 * compile-time type safety.
 */

// ValidName is only ever used as a compile-time bound on type parameters (Name <: ValidName),
// never as the type of an actual value, so opaque type would hide nothing (see #223).
// The underscore ban is enforced separately, at macro time, by ValidName.check.
type ValidName = String & Singleton

private[alpaca] object ValidName:
  // $COVERAGE-OFF$
  private[alpaca] def from[Name <: ValidName: Type](using quotes: Quotes): ValidName =
    import quotes.reflect.*
    TypeRepr.of[Name] match
      case ConstantType(StringConstant(str)) => str
      case x => raiseShouldNeverBeCalled(x.show)

  /**
   * Validates a token name during macro expansion.
   *
   * Token names must not be an underscore (_) as that would be invalid.
   *
   * @param name the token name to validate
   */
  private[alpaca] def check(using quotes: Quotes)(name: String, pos: quotes.reflect.Position): Unit = {
    import quotes.reflect.*

    name match
      case invalid @ "_" => report.errorAndAbort(show"Invalid token name: $invalid", pos)
      case _ =>
  }
// $COVERAGE-ON$
