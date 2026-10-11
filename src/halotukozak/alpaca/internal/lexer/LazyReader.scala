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
 *
 * @param reader the underlying Reader to read from
 * @param size   the exact number of chars `reader` yields
 */
final class LazyReader private[alpaca] (reader: Reader, private var size: Long) extends CharSequence, Closeable:
  // `buffer(start until end)` holds the chars read but not yet skipped; `start` may run past `end` when `from` skips
  // chars that have not been read yet
  private var buffer = new Array[Char](8192)
  private var start: Int = 0
  private var end: Int = 0

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
    buffer(pos + start)

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
    new String(buffer, start + this.start, len)

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
    start += count
    size -= count
    this

  override def toString: String = subSequence(0, length).toString

  override def close(): Unit = reader.close()

  @tailrec
  private def ensure(pos: Int): Unit =
    if pos + start >= end then
      if end == buffer.length then makeRoom()
      val charsRead = reader.read(buffer, end, buffer.length - end)
      if charsRead == -1 then
        throw IndexOutOfBoundsException(s"Position $pos is out of bounds for LazyReader of size $size")
      else
        end += charsRead
        ensure(pos)

  // drops the skipped chars from the front of the full buffer, and grows it if that frees nothing
  private def makeRoom(): Unit =
    val dropped = math.min(start, end)
    if dropped > 0 then
      System.arraycopy(buffer, dropped, buffer, 0, end - dropped)
      start -= dropped
      end -= dropped
    else buffer = java.util.Arrays.copyOf(buffer, buffer.length * 2)

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
