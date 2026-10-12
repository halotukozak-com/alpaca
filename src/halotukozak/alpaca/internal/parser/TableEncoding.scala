package halotukozak
package alpaca
package internal
package parser

/**
 * The plain-text form the parse table is compiled to, decoded once when the parser is initialized (#733, #738).
 *
 * The generated code carries this text, not code building the table, so it stays small and does not refer to the
 * table's internal classes. The text is a sequence of non-negative ints and length-prefixed strings:
 *
 *   - an int is written as base-32 digits, most significant first, each a printable ASCII character: a digit followed
 *     by more digits is `'!' + digit`, the last digit is `'A' + digit`;
 *   - a string is its length followed by its characters as they are.
 *
 * It starts with [[FormatVersion]]. The text is part of the binary contract between the generated code and the
 * library: code compiled by another version of the library may carry another format, which [[Reader]] rejects.
 */
private[parser] object TableEncoding:

  /** The version of the format, the first int of the text; bump it on every change of what the text holds. */
  final val FormatVersion = 1

  /**
   * The most characters in one string constant of the generated code: a class file stores a string in at most 65,535
   * bytes of modified UTF-8, which is at most 3 bytes per character.
   */
  final val ChunkLength = 16384

  private final val DigitBits = 5
  private final val DigitMask = (1 << DigitBits) - 1
  private final val MoreDigits = '!'
  private final val LastDigit = 'A'

  // $COVERAGE-OFF$
  /** Writes the text at compile time. */
  final class Writer:
    private val text = new StringBuilder

    writeInt(FormatVersion)

    def writeInt(value: Int): Unit =
      require(value >= 0, s"Only non-negative ints are encoded, got $value")
      var shift = DigitBits
      while shift < Integer.SIZE && (value >>> shift) != 0 do shift += DigitBits
      shift -= DigitBits
      while shift > 0 do
        text += (MoreDigits + ((value >>> shift) & DigitMask)).toChar
        shift -= DigitBits
      text += (LastDigit + (value & DigitMask)).toChar

    def writeString(value: String): Unit =
      writeInt(value.length)
      text ++= value

    /** The text, split into string constants of at most [[ChunkLength]] characters, never inside a surrogate pair. */
    def chunks: List[String] =
      val result = List.newBuilder[String]
      var start = 0
      while start < text.length do
        var end = (start + ChunkLength).min(text.length)
        if end < text.length && Character.isHighSurrogate(text.charAt(end - 1)) then end -= 1
        result += text.substring(start, end)
        start = end
      result.result()
  // $COVERAGE-ON$

  /**
   * Reads the text the generated code carries.
   *
   * @throws IllegalStateException if the text was written in another format
   */
  final class Reader(chunks: Array[String]):
    private val text = chunks.mkString
    private var position = 0

    locally:
      val version = readInt()
      if version != FormatVersion then
        throw IllegalStateException(
          s"This parser was compiled with a version of Alpaca whose parse table format ($version) differs from this one's ($FormatVersion); recompile it",
        )

    def readInt(): Int =
      var value = 0
      var char = text.charAt(position)
      while char < LastDigit do
        value = (value << DigitBits) | (char - MoreDigits)
        position += 1
        char = text.charAt(position)
      position += 1
      (value << DigitBits) | (char - LastDigit)

    def readString(): String =
      val length = readInt()
      val value = text.substring(position, position + length)
      position += length
      value
