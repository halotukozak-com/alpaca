package halotukozak
package alpaca

import scala.annotation.publicInBinary

/**
 * What [[production]] returns: the parser's named productions as members, each a [[Production]], so
 * `production.plus` is the production named `"plus"`.
 *
 * @note This is a compile-time only feature and can be used only inside `resolutions(...)`.
 */
transparent sealed trait ProductionSelector extends Selectable:
  def selectDynamic(name: String): Any

/**
 * A real (non-null) placeholder instance of [[ProductionSelector]].
 *
 * `production.someName` is meant to be intercepted and rewritten entirely at compile
 * time, but the underlying `resolutions(...)` call is an ordinary runtime function, so
 * this placeholder still gets evaluated and `.selectDynamic` still gets called on it.
 * It must be a real object rather than `null.asInstanceOf[...]`, otherwise that call
 * NPEs instead of reaching the `.after`/`.before` extension methods, which are inline
 * and discard their receiver/arguments entirely.
 */
@publicInBinary private[alpaca] object DummyProductionSelector extends ProductionSelector:
  override def selectDynamic(name: String): Any = null
