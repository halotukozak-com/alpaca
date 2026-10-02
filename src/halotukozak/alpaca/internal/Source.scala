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
   * Only valid during the macro expansion that created this source: grammar rules are declarations of the
   * lexer/parser being expanded, so they live in the same file as the expansion.
   */
  def toPosition(using quotes: Quotes): quotes.reflect.Position =
    import quotes.reflect.Position
    val sourceFile = Position.ofMacroExpansion.sourceFile
    if sourceFile.path != file then
      throw AlgorithmError(s"Source in $file is outside the macro expansion's file ${sourceFile.path}")
    Position(sourceFile, start, end)

object Source:
  def apply(using quotes: Quotes)(pos: quotes.reflect.Position): Source =
    Source(pos.startLine, pos.sourceFile.path, pos.start, pos.end)
