package halotukozak
package alpaca.internal

import halotukozak.commons.containsOnly

import halotukozak.made.{Made, MadeElem, MadeFieldElem, NotExists}

import scala.annotation.publicInBinary
import scala.compiletime.constValue

/**
 * Builds the initial lexer or parser context from the default values of its fields.
 */
@publicInBinary private[alpaca] object Defaults:
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
   * Derives a factory of `T` that builds it from the default values of its constructor parameters.
   *
   * @tparam T the case class to build
   * @return a factory of default instances
   */
  @publicInBinary inline private[alpaca] def derived[T: Made.Of as m]: () => T = inline m match
    case m: Made.ProductOf[T] =>
      () => m.fromTuple(collectDefaults(constValue[m.Label], m.elems).asInstanceOf[m.ElemTypes])
    case _ =>
      compiletime.error("The context should be a case class.")

  /** The [[derived]] factory of `T`, for a macro to splice into the code it generates at its expansion site. */
  private[alpaca] def derivedExpr[T: Type](using Quotes, Diagnostics): Expr[() => T] =
    import quotes.reflect.*
    Expr.summon[Made.Of[T]] match
      case Some(m) => '{ derived[T](using $m) }
      case None =>
        errorAndAbort(show"${Printable(TypeRepr.of[T].typeSymbol.name)} should be a case class.", Position.ofMacroExpansion)
// $COVERAGE-ON$
