package halotukozak
package alpaca
package internal

/**
 * `text` with the characters that would not show up in a message (line breaks, tabs, other control and format
 * characters) written as Scala escapes, so a token named `"\t"` reads as `\t` instead of a blank.
 */
private[alpaca] def printable(text: String): String =
  text.flatMap:
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c if c.isControl || Character.getType(c) == Character.FORMAT => "\\u%04x".format(c.toInt)
    case c => c.toString
