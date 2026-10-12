package halotukozak
package alpaca
package internal
package parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class TableEncodingTest extends AnyFunSuite with Matchers:

  private val ints = List(0, 1, 31, 32, 33, 1023, 1024, 65_535, 65_536, Int.MaxValue)

  test("ints and strings read back as written") {
    val writer = TableEncoding.Writer()
    ints.foreach(writer.writeInt)
    writer.writeString("")
    writer.writeString("ε \"\\\u0000 \uD83D\uDE00")

    val reader = TableEncoding.Reader(writer.chunks.toArray)
    ints.map(_ => reader.readInt()) shouldBe ints
    reader.readString() shouldBe ""
    reader.readString() shouldBe "ε \"\\\u0000 \uD83D\uDE00"
  }

  test("ints are written as printable ASCII") {
    val writer = TableEncoding.Writer()
    ints.foreach(writer.writeInt)
    assert(writer.chunks.mkString.forall(char => char >= '!' && char <= '`'))
  }

  test("a long text is split into chunks, never inside a surrogate pair") {
    // one of the offsets puts the surrogate pair across the first chunk boundary
    for offset <- 1 to 8 do {
      val writer = TableEncoding.Writer()
      val text = "a" * (TableEncoding.ChunkLength - offset) + "\uD83D\uDE00" + "b" * TableEncoding.ChunkLength
      writer.writeString(text)

      val chunks = writer.chunks
      chunks.size should be > 1
      all(chunks.map(_.length)) should be <= TableEncoding.ChunkLength
      assert(!chunks.exists(_.last.isHighSurrogate))
      TableEncoding.Reader(chunks.toArray).readString() shouldBe text
    }
  }

  test("a text in another format is rejected") {
    val writer = TableEncoding.Writer()
    writer.writeInt(TableEncoding.FormatVersion + 1)
    // the version this writer starts with is a single character
    val nextFormat = writer.chunks.mkString.drop(1)

    val error = the[IllegalStateException] thrownBy TableEncoding.Reader(Array(nextFormat))
    error.getMessage should include(s"(${TableEncoding.FormatVersion + 1}) differs from this one's")
  }
