package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.{lexer, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Using

final class LazyReaderFileTest extends AnyFunSuite with Matchers:

  test("LazyReader.from measures a file with multi-byte characters in chars, not bytes") {
    val content = "żółw 12\nźdźbło"
    val file = Files.createTempFile("alpaca-lazy-reader", ".txt")
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8)
      val Lexer = lexer:
        case w @ "[a-zżółźdbw]+" => Token["WORD"](w)
        case n @ "[0-9]+" => Token["NUM"](n.toInt)
        case "\\s+" => Token.Ignored

      Using.resource(LazyReader.from(file)): reader =>
        reader.length shouldBe content.length
        Lexer.tokenize(reader).getOrThrow.map(_.text) shouldBe List("żółw", "12", "źdźbło")
    } finally Files.delete(file)
  }
