package halotukozak
package alpaca

/**
 * What [[production]] returns: the parser's named productions as members, each a [[Production]], so
 * `production.plus` is the production named `"plus"`.
 *
 * @note This is a compile-time only feature and can be used only inside `resolutions(...)`.
 */
sealed trait ProductionSelector extends Selectable:
  def selectDynamic(name: String): Any
