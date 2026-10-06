package halotukozak
package alpaca.internal

import halotukozak.commons.containsOnly

import halotukozak.made.annotation.MetaAnnotation
import halotukozak.made.{Made, MadeFieldElem}

import scala.annotation.{implicitNotFound, publicInBinary}

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
  // A field without a default yields `made.NotExists` here; `requireDefaults` has already rejected those at compile time.
  inline private def collectDefaults(elems: Tuple)(using elems.type containsOnly MadeFieldElem): Tuple =
    inline elems match
      case EmptyTuple => EmptyTuple
      case _: (head *: tail) =>
        val head = elems.head.asInstanceOf[head & MadeFieldElem]
        head.default *: collectDefaults(elems.tail.asInstanceOf[tail & Tuple.Tail[elems.type]])

  // `made` only tells a missing default apart at run time (`default` is typed `Type | NotExists`), so the constructor
  // is inspected here instead.
  inline private def requireDefaults[T]: Unit = ${ requireDefaultsImpl[T] }

  // $COVERAGE-OFF$
  @publicInBinary private[internal] def requireDefaultsImpl[T: Type](using quotes: Quotes): Expr[Unit] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[T].typeSymbol
    // a made annotation (`@whenAbsent`, `@optionalParam`, ...) can supply the value instead
    val missing = tpe.primaryConstructor.paramSymss.flatten
      .filter(_.isTerm)
      .filterNot(param =>
        param.flags.is(Flags.HasDefault) || param.annotations.exists(_.tpe <:< TypeRepr.of[MetaAnnotation]),
      )
      .map(param => Printable(param.name))
    missing match
      case Nil => '{ () }
      case List(field) =>
        errorAndAbort(
          show"Field `$field` of ${Printable(tpe.name)} has no default value. Every field of a lexer or parser context needs one, so that the initial context can be built.",
          Position.ofMacroExpansion,
        )
      case fields =>
        errorAndAbort(
          show"Fields ${fields.map(field => show"`$field`").mkShow(", ")} of ${Printable(tpe.name)} have no default value. Every field of a lexer or parser context needs one, so that the initial context can be built.",
          Position.ofMacroExpansion,
        )
  }
  // $COVERAGE-ON$

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
      requireDefaults[T]
      () => m.fromTuple(collectDefaults(m.elems).asInstanceOf[m.ElemTypes])
    case _ =>
      compiletime.error("Cannot derive Empty for non-Product types.")
// $COVERAGE-ON$
