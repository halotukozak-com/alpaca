package halotukozak
package alpaca.internal

import halotukozak.commons.containsOnly

import scala.annotation.implicitNotFound
import halotukozak.made.Made
import halotukozak.made.MadeFieldElem
import halotukozak.made.label

/**
 * A type class for creating empty instances of types.
 *
 * This trait provides a way to create default instances of Product types (case classes)
 * by using their default parameter values, or the instance itself for objects. It extends
 * Function0 to act as a factory.
 *
 * @tparam T the type to create empty instances of
 */
@implicitNotFound("${T} should be a case class.")
trait Empty[T] extends (() => T)

object Empty:
  inline private def collectDefaults(elems: Tuple)(using elems.type containsOnly MadeFieldElem): Tuple =
    inline elems match
      case EmptyTuple => EmptyTuple
      case _: (head *: tail) =>
        val head = elems.head.asInstanceOf[head & MadeFieldElem]
        inline head.default match
          case _: Null =>
            compiletime.error("Cannot derive Empty for " + head.label + " because it has no default value.")
          case default =>
            default *: collectDefaults(elems.tail.asInstanceOf[tail & Tuple.Tail[elems.type]])

  /**
   * Automatically derives an Empty instance for any Product type with default parameters.
   *
   * This macro-based derivation uses the default values of constructor parameters
   * to create a factory for the type; for an object, the factory returns the object.
   *
   * @tparam T the Product type to derive Empty for
   * @return an Empty instance that creates default instances
   */
  inline given derived[T <: Product: Made.Of as m]: Empty[T] = inline m match
    case m: Made.SingletonOf[T] =>
      () => m.value
    case m: Made.ProductOf[T] =>
      () => m.fromTuple(collectDefaults(m.elems).asInstanceOf[m.ElemTypes])
    case _ =>
      compiletime.error("Cannot derive Empty for non-Product types.")
// $COVERAGE-ON$
