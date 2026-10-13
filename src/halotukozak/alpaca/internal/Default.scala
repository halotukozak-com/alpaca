package halotukozak
package alpaca.internal

import scala.collection.Factory

/**
 * A type class for creating default values of types.
 *
 * It is used internally to provide fallback values where needed, e.g. the value [[error]] gives.
 *
 * @tparam T the type to create defaults for
 */
private[internal] trait Default[+T] extends (() => T)

/**
 * Default instances for common types.
 *
 * `Unit` takes precedence when nothing constrains the type, so that e.g. [[error]] in statement position gives `()`.
 */
private[internal] object Default extends DefaultLowPriority:
  given Default[Unit] = () => ()

private[internal] trait DefaultLowPriority:
  given Default[Null] = () => null
  given [I[X] <: Iterable[X]] => (factory: Factory[Nothing, I[Nothing]]) => Default[I[Nothing]] =
    () => factory.fromSpecific(Nil)
  given Default[Option[Nothing]] = () => None
  given Default[Boolean] = () => false
