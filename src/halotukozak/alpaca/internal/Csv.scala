package halotukozak
package alpaca.internal

import scala.NamedTuple.NamedTuple
import scala.annotation.publicInBinary

/**
 * Represents data in CSV (Comma-Separated Values) format.
 *
 * This case class holds tabular data with headers and rows that can be
 * displayed as a CSV string. It is used internally for debug output.
 *
 * Note: cannot be made Tuple-based (see #225/#228) because ParseTable.toCsv
 * builds headers/rows from a grammar-dependent, runtime-known column count
 * (table.allSymbols), which has no fixed compile-time arity.
 *
 * @param headers the column headers
 * @param rows the data rows, each containing values for each column
 */
private[internal] final case class Csv(
  headers: List[Shown],
  rows: List[List[Shown]],
)

@publicInBinary private[internal] object Csv:

  /**
   * Showable instance for Csv that formats it as a comma-separated value string.
   *
   * The output has headers on the first line followed by data rows,
   * with values separated by commas.
   */

  /// todo it should be printed to the code lazy
  given Showable[Csv] = csv =>
    val header = csv.headers.mkShow(",")
    val rows = csv.rows.map(_.mkShow(",")).mkShow("\n")
    show"$header\n$rows"

  extension [N <: Tuple, V <: Tuple](rows: List[NamedTuple[N, V]]) {

    /**
     * Converts a list of named tuples to CSV format.
     *
     * The tuple field names become the CSV headers, and the values
     * become the data rows. This is useful for exporting structured
     * data to CSV.
     *
     * @return a Csv representation of the named tuples
     */

    inline def toCsv: Csv =
      Csv(
        compiletime.constValueTuple[N].toShowableList,
        rows.map(_.toTuple.toShowableList),
      )
  }

  extension [T <: Tuple](tuple: T)
    inline private def toShowableList = compiletime
      .summonAll[Tuple.Map[T, Showable]]
      .zip(tuple)
      .toList
      .asInstanceOf[List[(Showable[Any], Any)]]
      .map(_.show(_))
