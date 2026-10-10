package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.LazyReader
import halotukozak.alpaca.{lexer, withLazyReader, Token}
import org.scalatest.funsuite.AnyFunSuite

import java.io.StringReader
import scala.util.Using

final class LazyReaderTest extends AnyFunSuite:
  test("charAt should return correct character at position") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)): lazyReader =>
      assert(lazyReader.charAt(0) == 'h')
      assert(lazyReader.charAt(4) == 'o')
      assert(lazyReader.charAt(6) == 'w')
      assert(lazyReader.charAt(10) == 'd')
  }

  test("charAt should throw IndexOutOfBoundsException for position beyond end") {
    val reader = new StringReader("hello")
    Using.resource(new LazyReader(reader, 5)) { lazyReader =>
      val exception = intercept[IndexOutOfBoundsException]:
        lazyReader.charAt(10)

      assert(exception.getMessage.contains("Position 10 is out of bounds"))
    }
  }

  test("length should return correct value") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)): lazyReader =>
      assert(lazyReader.length == 11)
  }

  test("length should handle very large sizes by capping at Int.MaxValue") {
    val reader = new StringReader("test")
    Using.resource(new LazyReader(reader, Long.MaxValue)): lazyReader =>
      assert(lazyReader.length == Int.MaxValue)
  }

  test("subSequence should return correct substring") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)): lazyReader =>
      assert(lazyReader.subSequence(0, 5) == "hello")
      assert(lazyReader.subSequence(6, 11) == "world")
      assert(lazyReader.subSequence(0, 11) == "hello world")
      assert(lazyReader.subSequence(1, 4) == "ell")
  }

  test("from should remove characters from beginning and update length") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)) { lazyReader =>
      lazyReader.from(6): Unit

      assert(lazyReader.length == 5)
      assert(lazyReader.charAt(0) == 'w')
      assert(lazyReader.charAt(4) == 'd')
    }
  }

  test("charAt and subSequence read across a whole sentence") {
    withLazyReader("test content for file reading."): lazyReader =>
      assert(lazyReader.length == 30)
      assert(lazyReader.charAt(0) == 't')
      assert(lazyReader.charAt(4) == ' ')
      assert(lazyReader.subSequence(0, 4) == "test")
      assert(lazyReader.subSequence(5, 12) == "content")
  }

  test("empty string should work correctly") {
    val reader = new StringReader("")
    Using.resource(new LazyReader(reader, 0)) { lazyReader =>
      assert(lazyReader.length == 0)
      assert(lazyReader.subSequence(0, 0) == "")

      val exception = intercept[IndexOutOfBoundsException]:
        lazyReader.charAt(0)

      assert(exception.getMessage.contains("Position 0 is out of bounds"))
    }
  }

  test("from called multiple times should accumulate offset correctly") {
    val reader = new StringReader("abcdefghij")
    Using.resource(new LazyReader(reader, 10)) { lazyReader =>
      lazyReader.from(3): Unit
      assert(lazyReader.charAt(0) == 'd')
      assert(lazyReader.length == 7)

      lazyReader.from(4): Unit
      assert(lazyReader.charAt(0) == 'h')
      assert(lazyReader.length == 3)
    }
  }

  test("subSequence after from should return offset-adjusted content") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)): lazyReader =>
      lazyReader.from(6): Unit
      assert(lazyReader.subSequence(0, 5) == "world")
  }

  test("from advancing to exact end should produce length 0") {
    val reader = new StringReader("abc")
    Using.resource(new LazyReader(reader, 3)): lazyReader =>
      lazyReader.from(3): Unit
      assert(lazyReader.length == 0)
  }

  test("toString after from should return remaining content") {
    val reader = new StringReader("hello world")
    Using.resource(new LazyReader(reader, 11)): lazyReader =>
      lazyReader.from(6): Unit
      assert(lazyReader.toString == "world")
  }

  test("tokenizing a LazyReader past its compaction threshold gives the same lexemes as the String") {
    val Lexer = lexer:
      case w @ "[a-zżółw]+" => Token["WORD"](w)
      case "\\s+" => Token.Ignored
    val input = Iterator.fill(40000)("żółw").mkString(" ")

    withLazyReader(input): lazyReader =>
      assert(Lexer.tokenize(lazyReader).getOrThrow.map(_.text) == Lexer.tokenize(input).getOrThrow.map(_.text))
  }

  test("tokenizing a LazyReader consumes it") {
    val Lexer = lexer:
      case w @ "[a-z]+" => Token["WORD"](w)
      case " " => Token.Ignored

    withLazyReader("ab cd"): lazyReader =>
      assert(Lexer.tokenize(lazyReader).getOrThrow.map(_.text) == List("ab", "cd"))
      assert(lazyReader.length == 0)
  }
