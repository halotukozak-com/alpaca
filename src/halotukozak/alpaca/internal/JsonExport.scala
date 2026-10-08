package halotukozak
package alpaca.internal

import java.nio.file.{Files, Path}

import halotukozak.mcodec.{Json, MCodec}

import scala.util.control.NonFatal

/**
 * Writes a compile-time grammar export (lexer tokens, parser productions/table) to disk as JSON,
 * gated by [[GrammarExportSettings]].
 *
 * Kept dependency-light (mcodec, no ad-hoc JSON string building) since this runs inside the
 * compiler process for every consumer of the `lexer`/parser macros, not just for consumers of an
 * optional downstream tool.
 */
private[internal] object JsonExport:

  /**
   * Bumped only when the *shape* of an exported `.tokens.json`/`.productions.json`/`.table.json` file, or the meaning
   *  of its contents, changes -- not the library's own release version, which changes on every release regardless of
   *  whether the export did. A consumer (e.g. the IntelliJ plugin) reads this back to detect whether it understands
   *  the payload before parsing it.
   *
   *  Version 2: synthetic names for the end of the input and the start symbol, which the `.table.json` export carries
   *  itself.
   */
  private[internal] val ExportFormatVersion: Int = 2

  /**
   * Shape of a `.table.json` export, mirrored by the IntelliJ plugin's `ParseTableSpec`; changing it means bumping
   *  [[ExportFormatVersion]]. Generic because `Symbol` and `ParseAction` are private to the `parser` package.
   */
  private[internal] type TableFormat[Symbol, Action] =
    (endOfInput: String, start: String, states: List[List[TableFormat.Cell[Symbol, Action]]])

  private[internal] object TableFormat:
    type Cell[Symbol, Action] = (symbol: Symbol, action: Action)

// $COVERAGE-OFF$
  /**
   * Writes `value`, wrapped in a `{"version": ..., "context": ...}` envelope, to
   *  `<name>.<suffix>.json` in the configured export directory, if any; a no-op otherwise.
   */
  def maybeWrite[T: MCodec](name: String, suffix: String, value: => T)(using settings: GrammarExportSettings): Unit =
    settings.exportDirectory.foreach: dir =>
      val path = Path.of(dir).resolve(s"$name.$suffix.json")
      given MCodec[(version: Int, context: T)] = MCodec.derived
      val content = Json.write((version = ExportFormatVersion, context = value))
      try
        if !Files.exists(path) || Files.readString(path) != content then
          if path.getParent != null then Files.createDirectories(path.getParent): Unit
          Files.writeString(path, content): Unit
      catch
        case NonFatal(e) =>
          System.err.println(show"[alpaca] failed to write grammar export to $path: ${e.toString.showRaw}")
// $COVERAGE-ON$
