package halotukozak
package alpaca.internal

import scala.quoted.*

/**
 * Type alias for valid token names.
 *
 * Token names must be singleton strings (string literals) to enable
 * compile-time type safety.
 */

// ValidName is only ever used as a compile-time bound on type parameters (Name <: ValidName),
// never as the type of an actual value, so opaque type would hide nothing (see #223).
// The banned names are enforced separately, at macro time, by ValidName.apply.
type ValidName = String & Singleton

private[alpaca] object ValidName:
  // $COVERAGE-OFF$
  private[alpaca] def from[Name <: ValidName: Type](using quotes: Quotes): ValidName =
    import quotes.reflect.*
    TypeRepr.of[Name] match
      case ConstantType(StringConstant(str)) => str
      case x => raiseShouldNeverBeCalled(x.show)

  /**
   * Validates a token name during macro expansion, reporting an invalid one without aborting: the empty name and an
   * underscore (_) can't name a token.
   *
   * @param name the token name to validate
   * @return the name if it is valid, `None` once it has been reported
   */
  private[internal] def apply(using Quotes, Diagnostics)(name: String, pos: quotes.reflect.Position)
    : Option[ValidName] =
    name match
      case "" => error(show"Invalid token name: it is empty", pos); None
      case invalid @ "_" => error(show"Invalid token name: ${Printable(invalid)}", pos); None
      case valid => Some(valid.asInstanceOf[ValidName])
// $COVERAGE-ON$
