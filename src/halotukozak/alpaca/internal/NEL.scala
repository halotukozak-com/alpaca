package halotukozak
package alpaca.internal

import scala.annotation.publicInBinary

/**
 * An opaque type representing a non-empty sequence.
 *
 * Backed by [[Vector]] and exposed as a subtype of [[Seq]], with a
 * compile-time guarantee that it contains at least one element. Vector
 * gives O(1) `size` and effectively O(1) indexed access, which matters
 * on LR hot paths (`Item.isLastItem`, reduction dispatch, `nextSymbol`).
 *
 * @tparam A the element type
 */
opaque private[alpaca] type NEL[+A] <: Seq[A] = Vector[A]

@publicInBinary private[alpaca] object NEL:

  /**
   * Creates a non-empty list from a head element and optional tail elements.
   *
   * @param head the first element (required)
   * @param tail additional elements (optional)
   * @return a new NEL
   */
  inline def apply[A](inline head: A, inline tail: A*): NEL[A] = head +: tail.toVector

  /**
   * Pattern matching extractor for NEL.
   *
   * Extracts the head and tail of a non-empty list.
   *
   * @param list the list to deconstruct
   * @return (head, tail)
   */
  def unapply[A](list: NEL[A]): (A, Seq[A]) = (list.head, list.tail)

  /**
   * Unsafely converts a [[Seq]] to a NEL.
   *
   * This method performs a runtime check to ensure the sequence is non-empty.
   * Use with caution as it can throw an exception.
   *
   * @param list the sequence to convert
   * @return the sequence as a NEL
   * @throws IllegalArgumentException if the sequence is empty
   */
  private[internal] def unsafe[A](list: Seq[A]): NEL[A] =
    if list.isEmpty then throw IllegalArgumentException("Empty list cannot be converted to NEL")
    list.toVector

  // $COVERAGE-OFF$
  private[internal] def toExprImpl[A: {Type, ToExpr}](x: NEL[A])(using Quotes): Expr[NEL[A]] =
    '{ NEL(${ Expr(x.head) }, ${ ToExpr.SeqToExpr(x.tail) }*) }
// $COVERAGE-ON$
