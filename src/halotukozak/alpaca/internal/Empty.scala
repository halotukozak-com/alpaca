package halotukozak
package alpaca.internal

import halotukozak.commons.containsOnly

import halotukozak.made.{Made, MadeElem, MadeFieldElem, NotExists}

import scala.annotation.implicitNotFound
import scala.compiletime.constValue

/**
 * A type class for creating empty instances of types.
 *
 * This trait provides a way to create default instances of Product types (case classes)
 * by using their default parameter values. It extends Function0 to act as a factory.
 *
 * @tparam T the type to create empty instances of
 */
@implicitNotFound("${T} should be a case class.")
trait Empty[T] extends (() => T)

object Empty:
  inline private def collectDefaults(inline owner: String, elems: Tuple)(using elems.type containsOnly MadeFieldElem)
    : Tuple =
    inline elems match
      case EmptyTuple => EmptyTuple
      case _: (head *: tail) =>
        val head = elems.head.asInstanceOf[head & MadeFieldElem]
        inline head.default match
          case _: NotExists =>
            compiletime.error(
              "Field `" + constValue[MadeElem.ExtractLabel[head]] + "` of " + owner +
                " has no default value. Every field of a lexer or parser context needs one, so that the initial context can be built.",
            )
          case default =>
            default *: collectDefaults(owner, elems.tail.asInstanceOf[tail & Tuple.Tail[elems.type]])

  /**
   * Automatically derives an Empty instance for any Product type with default parameters.
   *
   * This macro-based derivation uses the default values of constructor parameters
   * to create a factory for the type.
   *
   * @tparam T the Product type to derive Empty for
   * @return an Empty instance that creates default instances
   */
  inline given derived[T <: Product: Made.Of as m]: Empty[T] = inline m match
    case m: Made.ProductOf[T] =>
      () => m.fromTuple(collectDefaults(constValue[m.Label], m.elems).asInstanceOf[m.ElemTypes])
    case _ =>
      compiletime.error("Cannot derive Empty for non-Product types.")
// $COVERAGE-ON$
