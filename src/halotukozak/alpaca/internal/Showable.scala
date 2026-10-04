package halotukozak
package alpaca
package internal

import halotukozak.commons.*
import halotukozak.made.*

import scala.NamedTuple.NamedTuple
import scala.annotation.publicInBinary

/**
 * A type class for converting values to their string representation.
 *
 * This trait provides a `show` method that can be used to display values
 * in a human-readable format. It's similar to `toString` but more controlled
 * and composable.
 *
 * @tparam T the type to show
 */
private[internal] trait Showable[-T]:
  /**
   * Extension method to convert a value to its string representation.
   *
   * @return the string representation of the value
   */
  extension (t: T) def show: Shown

  def transform[U](f: U => T): Showable[U] = u => f(u).show

/** String interpolator for values that have Showable instances. */
extension (sc: StringContext) @publicInBinary private[internal] def show(args: Shown*): Shown = sc.s(args*)

/**
 * An opaque type representing a string that has been shown.
 *
 * Used to ensure type safety in string interpolation.
 */
opaque into private[internal] type Shown <: String = String

private[internal] object Shown:

  /**
   * Implicit conversion from any Showable type to Shown.
   *
   * @tparam T the type with a Showable instance
   */
  given [T: Showable] => Conversion[T, Shown] = _.show

private[internal] object Showable:
  /** Showable instance for String (identity). */
  given Showable[String] = (_.asInstanceOf[Shown])

  /** Showable instance for Int. */
  given Showable[Int] = fromToString

  /** Showable instance for Long. */
  given Showable[Long] = fromToString

  /** Showable instance for Double. */
  given Showable[Double] = fromToString

  /** Showable instance for Float. */
  given Showable[Float] = fromToString

  /** Showable instance for Boolean. */
  given Showable[Boolean] = fromToString

  /** Showable instance for Char. */
  given Showable[Char] = fromToString

  def fromToString[T]: Showable[T] = (_.toString)

  inline given [N <: Tuple, V <: Tuple] => (m: Made.Of[NamedTuple[N, V]]) => Showable[NamedTuple[N, V]] =
    derived[NamedTuple[N, V]](using m)

  // $COVERAGE-OFF$
  given [T] => (quotes: Quotes) => Showable[Expr[T]] =
    import quotes.reflect.*
    summon[Showable[Tree]].transform(_.asTerm)

  given [T] => (quotes: Quotes) => Showable[quotes.reflect.TypeRepr] = tpe =>
    show"[${quotes.reflect.Printer.TypeReprShortCode.show(tpe)}](${quotes.reflect.Printer.TypeReprStructure.show(tpe)})"

  given [T] => (quotes: Quotes) => Showable[Type[T]] =
    import quotes.reflect.*
    summon[Showable[TypeRepr]].transform(TypeRepr.of(using _))

  given Showable[Position] = fromToString

  given Showable[java.nio.file.Path] = fromToString

  given Showable[Throwable] = fromToString

  // its toString is written to read as a message
  given Showable[halotukozak.regex.RegexParseError] = fromToString

  given (quotes: Quotes) => Showable[quotes.reflect.Tree] =
    quotes.reflect.Printer.TreeShortCode.show(_)

  given (quotes: Quotes) => Showable[quotes.reflect.Symbol] = (_.name)
  // $COVERAGE-ON$

  given [A: Showable, B: Showable] => Showable[(A, B)] = (a, b) => show"$a : $b"

  /**
   * Automatically derives a Showable instance for Product types (case classes).
   *
   * Creates a representation in the form: `ClassName(field1: value1, field2: value2)`.
   *
   * @tparam T the Product type to derive Showable for
   * @param m the Mirror.ProductOf for type T
   * @return a Showable instance
   */
  inline def derived[T: Made.Of as m]: Showable[T] = t =>
    inline m match
      case m: Made.ProductOf[T] =>
        val name = m.label
        val fields = m.elemLabels.toArrayOf[String]
        val showables =
          compiletime.summonAll[Tuple.Map[m.ElemTypes, Showable]].toArrayOf[Showable[Any]](using containsOnly.refl)
        val values = t.asInstanceOf[Product].productIterator
        val shown = showables.zip(values).map(_.show(_))
        if showables.isEmpty then show"$name"
        else show"$name(${fields.zip(shown).map((f, v) => s"$f: $v").mkShow(", ")})"
      case m: Made.SumOf[T] =>
        val name = m.label
        val showables =
          compiletime.summonAll[Tuple.Map[m.ElemTypes, Showable]].toArrayOf[Showable[Any]](using containsOnly.refl)
        val index = m.ordinal(t)
        val shown = showables(index).show(t)
        show"$name($shown)"

extension [C[X] <: Iterable[X], T: Showable](c: C[T]) {

  /**
   * Creates a string representation with custom start, separator, and end strings.
   *
   * @param start the string to prepend
   * @param sep   the separator between elements
   * @param end   the string to append
   * @return the formatted string
   */
  private[internal] def mkShow(start: String, sep: String, end: String): Shown =
    c.iterator.map(_.show).mkString(start, sep, end)

  /**
   * Creates a string representation with a custom separator.
   *
   * @param sep the separator between elements
   * @return the formatted string
   */
  @publicInBinary private[internal] def mkShow(sep: String): Shown = mkShow("", sep, "")

  /**
   * Creates a string representation with elements concatenated.
   *
   * @return the concatenated string
   */
  private[internal] def mkShow: Shown = mkShow("")
}

extension [T: Showable](it: Iterator[T])
  private[internal] def mkShow(start: String, sep: String, end: String): Shown = it.map(_.show).mkString(start, sep, end)
  private[internal] def mkShow(sep: String): Shown = mkShow("", sep, "")
  private[internal] def mkShow: Shown = mkShow("")
