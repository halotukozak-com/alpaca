package halotukozak.alpaca.internal

import halotukozak.mcodec.MCodec

import scala.quoted.ToExprFactory

/**
 * Where a lexer or parser rule is defined in the grammar's source.
 *
 * @param line  the 0-based line of the definition
 * @param file  the path of the source file
 * @param start the offset of the definition's first character in `file`
 * @param end   the offset just past the definition's last character in `file`
 */
case class Source(line: Int, file: String, start: Int, end: Int) derives MCodec, ToExprFactory:

  /**
   * The definition's position, for reporting compile errors at it.
   *
   * A position can only be rebuilt in the macro expansion's own file (reflection can't open another source file), so
   * this is empty for a definition elsewhere, such as a `given Resolutions` declared next to, not in, the parser.
   */
  def toPosition(using quotes: Quotes): Option[quotes.reflect.Position] =
    import quotes.reflect.Position
    val sourceFile = Position.ofMacroExpansion.sourceFile
    Option.when(sourceFile.path == file)(Position(sourceFile, start, end))

object Source:
  def apply(using quotes: Quotes)(pos: quotes.reflect.Position): Source =
    Source(pos.startLine, pos.sourceFile.path, pos.start, pos.end)
