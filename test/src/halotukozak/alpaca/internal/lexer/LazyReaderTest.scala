package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.LazyReader
import halotukozak.alpaca.{lexer, withLazyReader, ErrorHandling, LexerCtx, LexerError, Result, Token}
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

  test("LazyReader.from should create LazyReader from file path") {
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

  test("lexer errors and peek past the compaction threshold are the same as for the String") {
    var peeked = List.empty[String]
    given ErrorHandling[LexerCtx.Default, LexerError] = (ctx, _) =>
      peeked = peeked :+ ctx.peek(5)
      ErrorHandling.Strategy.SkipOne

    val Lexer = lexer:
      case w @ "[a-z]+" => Token["WORD"](w)
      case "\\s+" => Token.Ignored
    val line = "word " * 10 + "\n"
    // the first error comes after more than 64K chars have been consumed and compacted away
    val input = line * 1500 + "!word\n" + line * 10 + "?"

    def errorsOf(text: CharSequence) = Lexer.tokenize(text) match
      case Result.Failure(_, _, errors) => errors.map(error => (error.unexpected, error.line, error.column))
      case Result.Success(_, _) => fail("expected a failure")

    val fromReader = withLazyReader(input)(errorsOf)
    assert(fromReader == List(("!", 1501, 1), ("?", 1512, 1)))
    assert(peeked == List("!word", "?"))
    peeked = Nil
    assert(errorsOf(input) == fromReader)
    assert(peeked == List("!word", "?"))
  }

  test("a surrogate pair split across two reads is one code point") {
    // the reader is read 8192 chars at a time, so the emoji's two chars arrive in different reads
    val input = "a" * 8191 + "😀" + "a"
    assert(input.charAt(8191).isHighSurrogate)

    val Lexer = lexer:
      case a @ "a+" => Token["A"](a)
      case "😀" => Token["EMOJI"]
    withLazyReader(input): lazyReader =>
      assert(
        Lexer.tokenize(lazyReader).getOrThrow.map(lexeme => (lexeme.name, lexeme.text.length, lexeme.column)) ==
          List(("A", 8191, 1), ("EMOJI", 2, 8192), ("A", 1, 8193)),
      )

    val OnlyA = lexer:
      case a @ "a+" => Token["A"](a)
    withLazyReader(input): lazyReader =>
      OnlyA.tokenize(lazyReader) match
        case Result.Failure(_, _, errors) =>
          assert(errors.map(error => (error.unexpected, error.column)) == List(("😀", 8192)))
        case Result.Success(_, _) => fail("expected a failure")
  }

  test("tokenizing a LazyReader consumes it") {
    val Lexer = lexer:
      case w @ "[a-z]+" => Token["WORD"](w)
      case " " => Token.Ignored

    withLazyReader("ab cd"): lazyReader =>
      assert(Lexer.tokenize(lazyReader).getOrThrow.map(_.text) == List("ab", "cd"))
      assert(lazyReader.length == 0)
  }
