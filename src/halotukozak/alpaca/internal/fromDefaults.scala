package halotukozak
package alpaca.internal

import halotukozak.commons.containsOnly

import halotukozak.made.{Made, MadeElem, MadeFieldElem, NotExists}

import scala.annotation.publicInBinary
import scala.compiletime.constValue
import scala.quoted.*

/** The factory of `T` built from its fields' default values, for a macro to splice in. */
private[alpaca] def fromDefaults[T: Type](using Quotes, Diagnostics): Expr[() => T] =
  import quotes.reflect.*
  Expr.summon[Made.Of[T]] match
    case Some(m) => '{ deriveFromDefaults[T](using $m) }
    case None =>
      errorAndAbort(
        show"${Printable(TypeRepr.of[T].typeSymbol.name)} should be a case class.",
        Position.ofMacroExpansion,
      )

@publicInBinary inline private def deriveFromDefaults[T: Made.Of as m]: () => T = inline m match
  case m: Made.ProductOf[T] =>
    () => m.fromTuple(collectDefaults(constValue[m.Label], m.elems).asInstanceOf[m.ElemTypes])
  case _ =>
    compiletime.error("The context should be a case class.")

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
// $COVERAGE-ON$
