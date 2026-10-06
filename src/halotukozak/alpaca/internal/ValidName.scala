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
// The banned and reserved names are enforced separately, at macro time, by ValidName.check.
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
   * Token names must not be empty or an underscore (_), and must not be one of the names the parser reserves for
   * its own terminals: `$` (the end of the input) and `ε` (the empty sequence).
   *
   * @param name the token name to validate
   */
  private[internal] def check(using Quotes, Diagnostics)(name: String, pos: quotes.reflect.Position): Unit =
    name match
      case invalid @ "_" => errorAndAbort(show"Invalid token name: ${Printable(invalid)}", pos)
      case "" => errorAndAbort(show"Invalid token name: it is empty", pos)
      case reserved @ ("$" | "ε") =>
        val quoted = show"\"${Printable(reserved)}\""
        errorAndAbort(
          show"Token name $quoted is reserved: the parser uses \"$$\" for the end of the input and \"ε\" for the empty sequence",
          pos,
        )
      case _ =>
// $COVERAGE-ON$
