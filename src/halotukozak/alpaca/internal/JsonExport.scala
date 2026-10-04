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
   * Bumped only when the *shape* of an exported `.tokens.json`/`.productions.json`/`.table.json`
   *  file changes -- not the library's own release version, which changes on every release
   *  regardless of whether the export shape did. A consumer (e.g. the IntelliJ plugin) reads this
   *  back to detect whether it understands the payload before parsing it.
   */
  private[internal] val ExportFormatVersion: Int = 1

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
        case NonFatal(e) => System.err.println(show"[alpaca] failed to write grammar export to $path: $e")
// $COVERAGE-ON$
