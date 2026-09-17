package halotukozak
package alpaca
package internal
package lexer

import halotukozak.alpaca.internal.AlpacaException

/**
 * Exception thrown when a token pattern is shadowed by another pattern.
 *
 * This exception is thrown during lexer compilation when one regex pattern
 * will never match because an earlier pattern always matches first.
 *
 * @param first the pattern that is shadowed
 * @param second the pattern that shadows it
 */
private[alpaca] final class ShadowException(val first: String, val second: String)
  extends AlpacaException(show"Pattern $first is shadowed by $second")

private[alpaca] object ShadowException:
  def unapply(e: ShadowException): (String, String) = (e.first, e.second)
