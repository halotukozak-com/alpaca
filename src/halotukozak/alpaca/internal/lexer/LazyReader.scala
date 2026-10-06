package halotukozak
package alpaca
package internal
package lexer

import java.io.{Closeable, Reader}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Using

/**
 * A lazy character sequence that reads from a Reader on demand.
 *
 * This class is useful for tokenizing large files without loading them
 * entirely into memory. It buffers characters as they are accessed and
 * can efficiently skip over processed characters.
 *
 * @param reader the underlying Reader to read from
 * @param size   the total size of the input (if known)
 */
//todo: use Ox
final class LazyReader(private val reader: Reader, private var size: Long) extends CharSequence, Closeable:
  private val buffer = mutable.ArrayDeque.empty[Char]
  private val chunk = new Array[Char](8192)
  private var offset: Int = 0 // logical offset into buffer

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
    ensure(pos + offset)
    buffer(pos + offset)

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
    ensure(end - 1 + offset)
    val len = end - start
    require(len >= 0, s"Invalid subsequence range: start=$start, end=$end")
    val base = start + offset
    val out = Array.better.tabulate(len)(i => buffer(base + i))
    new String(out)

  /**
   * Skips the first count characters.
   *
   * This is used to advance past tokens that have been processed.
   * Uses O(1) logical offset advancement instead of O(n) buffer shifting.
   * Periodically compacts the buffer when offset exceeds 64KB to bound memory waste.
   *
   * @param count the number of characters to skip
   * @return this LazyReader for chaining
   */
  def from(count: Int): LazyReader =
    offset += count
    size -= count
    // Compact when offset exceeds 64KB to bound memory waste
    if offset > 65536 then
      buffer.remove(0, offset)
      offset = 0
    this

  override def toString: String = subSequence(0, length).toString

  override def close(): Unit = reader.close()

  @tailrec
  private def ensure(pos: Int): Unit =
    if pos >= buffer.length then
      val charsRead = reader.read(chunk)
      if charsRead == -1 then
        throw IndexOutOfBoundsException(s"Position $pos is out of bounds for LazyReader of size $size")
      else
        buffer.appendAll(chunk.iterator.take(charsRead))
        ensure(pos)

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
