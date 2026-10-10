package halotukozak
package alpaca.internal

import scala.collection.Factory
import scala.quoted.*

/**
 * A type class for creating default values of types.
 *
 * This trait provides a way to obtain default instances of various types,
 * including primitives, collections, and macro-related types like Expr and Type.
 * It is used internally to provide fallback values where needed.
 *
 * @tparam T the type to create defaults for
 */
private[internal] trait Default[+T] extends (() => T):
  /**
   * Transforms the default value using the provided function.
   *
   * @tparam A the result type
   * @param f the transformation function
   * @return a new Default that applies the transformation
   */
  def transform[A](f: T => A): Default[A] = () => f(apply())

/**
 * Default instances for common types.
 */
private[internal] object Default:
  def apply[T](using d: Default[T]): Default[T] = d

  given Default[Unit] = () => ()
  given Default[Boolean] = () => false
  given Default[Int] = () => 0
  given Default[String] = () => ""
  given Default[ValidName] = () => ""
  given [I[X] <: Iterable[X]] => (factory: Factory[Nothing, I[Nothing]]) => Default[I[Nothing]] =
    () => factory.fromSpecific(Nil)
  given Default[IterableOnce[Nothing]] = () => Iterable.empty
  given Default[Option[Nothing]] = () => None

  // $COVERAGE-OFF$
  given [T] => (quotes: Quotes) => Default[Expr[T]] = () => '{ ??? }

  given [T <: AnyKind] => (quotes: Quotes) => Default[Type[T]] = () => Type.of[Nothing].asInstanceOf[Type[T]]

  given [T] => (quotes: Quotes) => Default[quotes.reflect.Term] =
    import quotes.reflect.*
    Default[Expr[T]].transform(_.asTerm)

  given (quotes: Quotes) => Default[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    () => TypeRepr.of[Nothing]

  given (quotes: Quotes) => Default[quotes.reflect.Symbol] =
    import quotes.reflect.*
    () => Symbol.noSymbol
  // $COVERAGE-ON$

  given [H: Default as head, T <: Tuple: Default as tail] => Default[H *: T] = () => head() *: tail()

  given Default[EmptyTuple] = () => EmptyTuple
