package halotukozak
package alpaca
package internal
package lexer

import java.io.{Closeable, Reader}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.util.Using

/**
 * A lazy character sequence that reads from a Reader on demand.
 *
 * This class is useful for tokenizing large files without loading them
 * entirely into memory. It buffers characters as they are accessed and
 * can efficiently skip over processed characters.
 *
 * Like the `Reader` it wraps, it is consumed as it is read. Create it with [[LazyReader.from]].
 */
// `reader` is the underlying Reader to read from; `size` is the exact number of chars it yields.
final class LazyReader private[alpaca] (reader: Reader, private var size: Long) extends CharSequence, Closeable:
  // `buffer(bufferStart until bufferEnd)` holds the chars read but not yet skipped; `bufferStart` may run past
  // `bufferEnd` when `from` skips chars that have not been read yet
  private var buffer = new Array[Char](8192)
  private var bufferStart: Int = 0
  private var bufferEnd: Int = 0

  /**
   * Gets the character at the specified position.
   *
   * This will read from the underlying Reader if necessary.
   *
   * @param pos the position (0-based)
   * @return the character at that position
   * @throws IndexOutOfBoundsException if the position is beyond the end of input
   */
  def charAt(pos: Int): Char =
    ensure(pos)
    buffer(pos + bufferStart)

  /**
   * Gets the length of the input.
   *
   * @return the length, capped at Int.MaxValue
   */
  def length: Int = math.min(Int.MaxValue, size).toInt

  /**
   * Creates a subsequence.
   *
   * @param start the start position (inclusive)
   * @param end the end position (exclusive)
   * @return a string containing the subsequence
   */
  def subSequence(start: Int, end: Int): CharSequence =
    ensure(end - 1)
    val len = end - start
    require(len >= 0, s"Invalid subsequence range: start=$start, end=$end")
    new String(buffer, start + bufferStart, len)

  /**
   * Skips the first count characters.
   *
   * This is used to advance past tokens that have been processed.
   * Only advances the start index; the skipped chars are dropped from the buffer when it next fills up.
   *
   * @param count the number of characters to skip
   * @return this LazyReader for chaining
   */
  private[alpaca] def from(count: Int): LazyReader =
    bufferStart += count
    size -= count
    this

  override def toString: String = subSequence(0, length).toString

  override def close(): Unit = reader.close()

  @tailrec
  private def ensure(pos: Int): Unit =
    if pos + bufferStart >= bufferEnd then
      if bufferEnd == buffer.length then makeRoom()
      val charsRead = reader.read(buffer, bufferEnd, buffer.length - bufferEnd)
      if charsRead == -1 then
        throw IndexOutOfBoundsException(s"Position $pos is out of bounds for LazyReader of size $size")
      else
        bufferEnd += charsRead
        ensure(pos)

  // drops the skipped chars from the front of the full buffer, and grows it unless that frees at least half of it, so
  // that a long lookahead doesn't shift almost the whole buffer to read only a few chars
  private def makeRoom(): Unit =
    val dropped = math.min(bufferStart, bufferEnd)
    val target = if dropped >= buffer.length / 2 then buffer else new Array[Char](buffer.length * 2)
    System.arraycopy(buffer, dropped, target, 0, bufferEnd - dropped)
    buffer = target
    bufferStart -= dropped
    bufferEnd -= dropped

/**
 * Factory methods for creating LazyReader instances.
 */
object LazyReader:

  /**
   * Creates a LazyReader from a file path.
   *
   * @param path the path to the file
   * @param charset the character encoding (defaults to UTF-8)
   * @return a new LazyReader
   */
  def from(path: Path, charset: Charset = StandardCharsets.UTF_8): LazyReader =
    LazyReader(Files.newBufferedReader(path, charset), charCount(path, charset))

  // `length` counts chars, not bytes, so a file with multi-byte characters has to be decoded once to measure it
  private def charCount(path: Path, charset: Charset): Long =
    Using.resource(Files.newBufferedReader(path, charset)): reader =>
      @tailrec def loop(count: Long): Long = reader.skip(Long.MaxValue) match
        case 0 => count
        case skipped => loop(count + skipped)
      loop(0)
