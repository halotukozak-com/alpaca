package halotukozak
package alpaca
package internal

import halotukozak.mcodec.MCodec

import scala.annotation.publicInBinary

/**
 * Text that comes from the user's grammar or input -- a token, rule or production name, a pattern, unmatched input --
 * and so may hold characters that would not show up in a message (line breaks, tabs, other control and format
 * characters).
 *
 * Its [[Showable]] writes those as Scala escapes, so a token named `"\t"` reads as `\t` instead of a blank. It is not a
 * subtype of `String` on purpose: it reaches a message only through `show`, never as raw text. Use `raw` where the
 * text itself is needed, e.g. as a map key or a generated name.
 */
opaque private[alpaca] type Printable = String

// Summoned out here: inside `object Printable`, where `Printable` is `String`, the search would find its own codec.
private val stringCodec: MCodec[String] = MCodec[String]

@publicInBinary private[alpaca] object Printable:
  inline def apply(text: String): Printable = text

  /** `text` as [[Printable]], or `null` if it is `null`. */
  inline def nullable(text: String | Null): Printable | Null = text

  extension (text: Printable) inline def raw: String = text

  given Showable[Printable] = text =>
    val escaped = text.flatMap:
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl || Character.getType(c) == Character.FORMAT => "\\u%04x".format(c.toInt)
      case c => c.toString
    escaped.showRaw

  given Ordering[Printable] = Ordering.String

  // $COVERAGE-OFF$
  given MCodec[Printable] = stringCodec

  // The generated code is outside this object, where `Printable` is not a `String`, so it has to build one. The
  // `ToExpr[String]` is passed explicitly: in here `Printable` is `String`, so a search would find this very given.
  given ToExpr[Printable]:
    def apply(text: Printable)(using Quotes): Expr[Printable] =
      '{ Printable(${ Expr(text)(using ToExpr.StringToExpr[String]) }) }
  // $COVERAGE-ON$
