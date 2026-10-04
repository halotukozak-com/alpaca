package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.{lexer, withLazyReader, Token}
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.typeCheckErrors

final class LexerTest extends AnyFunSuite with Matchers with LoneElement:

  private type Shape = (String, Any, Map[String, Any])

  extension (lexeme: Lexeme[?, ?])
    private def shape = (
      name = lexeme.name,
      value = lexeme.value,
      fields = lexeme.fieldNames.iterator.zip(lexeme.fieldValues.iterator).toMap + ("text" -> lexeme.text),
    )

  private def fields(text: String, position: Int, line: Int): Map[String, Any] =
    Map[String, Any]("text" -> text, "position" -> position, "line" -> line)

  test("selectDynamic returns ctx fields and throws for missing keys") {
    val lexeme: Lexeme[?, ?] =
      new Lexeme("IDENTIFIER", "hello", "hello", Array("position", "line"), Array(6, 1))

    lexeme.text shouldBe "hello"
    lexeme.selectDynamic("position") shouldBe 6
    lexeme.selectDynamic("line") shouldBe 1
    intercept[NoSuchElementException](lexeme.selectDynamic("missing"))
  }

  test("selectDynamic falls through arrays in order to find the first match") {
    val lexeme = new Lexeme("T", (), "txt", Array("a", "b", "a"), Array(1, 2, 3))

    lexeme.selectDynamic("a") shouldBe 1
    lexeme.selectDynamic("b") shouldBe 2
  }

  test("tokenize simple identifier") {
    val Lexer = lexer:
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)

    val (_, lexemes) = Lexer.tokenize("hello")
    assert(lexemes.map(_.shape) == List[Shape](("IDENTIFIER", "hello", fields("hello", 6, 1))))
  }

  test("tokenize with whitespace ignored") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number)
      case "\\+" => Token["PLUS"]
      case "\\s+" => Token.Ignored

    val (_, lexemes) = Lexer.tokenize("42 + 13")

    assert(
      lexemes.map(_.shape) == List[Shape](
        ("NUMBER", "42", fields("42", 3, 1)),
        ("PLUS", (), fields("+", 5, 1)),
        ("NUMBER", "13", fields("13", 8, 1)),
      ),
    )
  }

  test("tokenize empty string") {
    val Lexer = lexer:
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)

    val (_, lexemes) = Lexer.tokenize("")
    assert(lexemes == Nil)
  }

  test("throw exception for unexpected character") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    val exception = intercept[RuntimeException]:
      Lexer.tokenize("123abc")

    assert(exception.getMessage.contains("Unexpected character at line 1, position 4: 'a'"))
  }

  test("tokenize complex expression") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number)
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)
      case "\\+" => Token["PLUS"]
      case "-" => Token["MINUS"]
      case "\\*" => Token["MULTIPLY"]
      case "\\(" => Token["LPAREN"]
      case "\\)" => Token["RPAREN"]
      case "\\s+" => Token.Ignored

    val (_, lexemes) = Lexer.tokenize("(x + 42) * y - 1")

    assert(
      lexemes.map(_.shape) == List[Shape](
        ("LPAREN", (), fields("(", 2, 1)),
        ("IDENTIFIER", "x", fields("x", 3, 1)),
        ("PLUS", (), fields("+", 5, 1)),
        ("NUMBER", "42", fields("42", 8, 1)),
        ("RPAREN", (), fields(")", 9, 1)),
        ("MULTIPLY", (), fields("*", 11, 1)),
        ("IDENTIFIER", "y", fields("y", 13, 1)),
        ("MINUS", (), fields("-", 15, 1)),
        ("NUMBER", "1", fields("1", 17, 1)),
      ),
    )
  }

  test("cross-case shadowing names both tokens and suggests a fix") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "[a-zA-Z_][a-zA-Z0-9_]*" => Token["IDENTIFIER"]
        case "[a-zA-Z]+" => Token["ALPHABETIC"]
      """).loneElement.message shouldBe
      """Token "ALPHABETIC" can never match: every input it matches is already matched by "IDENTIFIER",
        |which is tried first because it's defined earlier.
        |Consider reordering the cases so "ALPHABETIC" comes first, or merging them into one case with
        |alternatives, e.g.: case x @ ("IDENTIFIER" | "ALPHABETIC") => Token[x]""".stripMargin
  }

  test("within-case alternatives where one is a prefix of another both stay reachable - longer first") {
    val Lexer = lexer:
      case ">=" | ">" => Token["GREATER"]
      case "\\s+" => Token.Ignored

    val (_, lexemes) = Lexer.tokenize(">= >")
    assert(lexemes.map(_.shape.fields("text")) == List(">=", ">"))
  }

  test("within-case alternatives where one is a prefix of another both stay reachable - shorter first") {
    val Lexer = lexer:
      case ">" | ">=" => Token["GREATER"]
      case "\\s+" => Token.Ignored

    val (_, lexemes) = Lexer.tokenize(">= >")
    assert(lexemes.map(_.shape.fields("text")) == List(">=", ">"))
  }

  test("invalid regex in a named single pattern names the token") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "(" => Token["LPAREN"]
      """).loneElement.message should startWith("""Invalid regex pattern for token "LPAREN": """)
  }

  test("invalid regex in a bound alternative names the offending alternative") {
    typeCheckErrors("""
      val Lexer = lexer:
        case x @ ("a" | "(") => Token[x.type]
      """).loneElement.message should startWith("""Invalid regex pattern for token "(": """)
  }

  test("duplicate token name points at the redefinition and suggests alternatives") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "a" => Token["X"]
        case "b" => Token["X"]
      """).loneElement.message shouldBe
      """Token name "X" is defined 2 times. Combine the patterns into a single case using alternatives, """ +
      """e.g.: case x @ ("pattern1" | "pattern2") => Token[x]"""
  }

  test("track line and position across newlines") {
    val Lexer = lexer:
      case id @ "[a-zA-Z]+" => Token["IDENTIFIER"](id)
      case "\\s+" => Token.Ignored

    val (ctx, lexemes) = Lexer.tokenize("abc\ndef")
    assert(
      lexemes.map(_.shape) == List[Shape](
        ("IDENTIFIER", "abc", fields("abc", 4, 1)),
        ("IDENTIFIER", "def", fields("def", 4, 2)),
      ),
    )
    ctx.line shouldBe 2
    ctx.position shouldBe 4
  }

  test("tokenize file") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number)
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)
      case "\\+" => Token["PLUS"]
      case "-" => Token["MINUS"]
      case "\\*" => Token["MULTIPLY"]
      case "\\(" => Token["LPAREN"]
      case "\\)" => Token["RPAREN"]
      case "\\s+" => Token.Ignored

    withLazyReader("(x + 42) * y - 1") { reader =>
      val (_, lexemes) = Lexer.tokenize(reader)

      assert(
        lexemes.map(_.shape) == List[Shape](
          ("LPAREN", (), fields("(", 2, 1)),
          ("IDENTIFIER", "x", fields("x", 3, 1)),
          ("PLUS", (), fields("+", 5, 1)),
          ("NUMBER", "42", fields("42", 8, 1)),
          ("RPAREN", (), fields(")", 9, 1)),
          ("MULTIPLY", (), fields("*", 11, 1)),
          ("IDENTIFIER", "y", fields("y", 13, 1)),
          ("MINUS", (), fields("-", 15, 1)),
          ("NUMBER", "1", fields("1", 17, 1)),
        ),
      )
    }
  }

  test("guard in a lexer case is reported at the guard") {
    val guard: scala.compiletime.testing.Error = typeCheckErrors("""
    val GuardedLexer = lexer:
      case "a" => Token["a"]
      case "b" if true => Token["b"]
    """).loneElement
    guard.message shouldBe "Guards are not supported yet"
    guard.lineContent.trim shouldBe "case \"b\" if true => Token[\"b\"]"
    guard.column shouldBe 18
  }

  test("invalid token name is reported at its case") {
    val invalid: scala.compiletime.testing.Error = typeCheckErrors("""
    val UnderscoreLexer = lexer:
      case "a" => Token["a"]
      case "b" => Token["_"]
    """).loneElement
    invalid.message shouldBe "Invalid token name: _"
    invalid.lineContent.trim shouldBe "case \"b\" => Token[\"_\"]"
  }
