package halotukozak.alpaca.internal

import halotukozak.mcodec.MCodec

import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.quoted.*
import scala.util.Try

/**
 * Where a lexer or parser rule is defined in the grammar's source.
 *
 * @param line  the 0-based line of the definition
 * @param file  the absolute path of the source file; exported relative to its project root (see [[Source.exportPath]])
 * @param start the offset of the definition's first character in `file`
 * @param end   the offset just past the definition's last character in `file`
 */
private[alpaca] final case class Source(line: Int, file: String, start: Int, end: Int):

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

private[alpaca] object Source:
  def apply(using quotes: Quotes)(pos: quotes.reflect.Position): Source =
    Source(pos.startLine, pos.sourceFile.path, pos.start, pos.end)

  // $COVERAGE-OFF$
  /** The export codec: the same shape as the case class, with `file` written by [[exportPath]]. */
  given MCodec[Source] = MCodec
    .derived[(line: Int, file: String, start: Int, end: Int)]
    .transform(
      onWrite = source => (line = source.line, file = exportPath(source.file), start = source.start, end = source.end),
      onRead = exported => Source(exported.line, exported.file, exported.start, exported.end),
    )
  // $COVERAGE-ON$

  /**
   * `file` as written to a grammar export: relative to its project root, the nearest enclosing directory with a `.git`
   * entry (a directory, or a file in a worktree or submodule), with `/` separators, so exports of the same code don't
   * differ between checkouts or leak the build machine's layout. A file outside any repository stays absolute.
   *
   * The repository root is used because nothing the macro can see knows the project root: the compiler's working
   * directory belongs to whatever process runs it (a Gradle or Bloop daemon is started elsewhere), and `-sourceroot`
   * is only reachable through compiler internals.
   */
  private[internal] def exportPath(file: String): String =
    val absolute = Path.of(file).toAbsolutePath.normalize
    projectRoot(absolute.getParent).flatMap(root => Try(root.relativize(absolute)).toOption) match
      case Some(relative) => relative.toString.replace('\\', '/')
      case None => absolute.toString

  @tailrec
  private def projectRoot(directory: Path | Null): Option[Path] = directory match
    case null => None
    case directory: Path if Files.exists(directory.resolve(".git")) => Some(directory)
    case directory: Path => projectRoot(directory.getParent)
