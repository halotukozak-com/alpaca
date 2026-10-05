package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.{lexer, withLazyReader, LexError, LexerException, Result, Token}
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

    val lexemes = Lexer.tokenize("hello").getOrThrow
    assert(lexemes.map(_.shape) == List[Shape](("IDENTIFIER", "hello", fields("hello", 6, 1))))
  }

  test("tokenize with whitespace ignored") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number)
      case "\\+" => Token["PLUS"]
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("42 + 13").getOrThrow

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

    val lexemes = Lexer.tokenize("").getOrThrow
    assert(lexemes == Nil)
  }

  test("unexpected character fails with a LexError and the lexemes before it are not recovered") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    Lexer.tokenize("123abc") match
      case Result.Failure(_, recovered, errors) =>
        recovered shouldBe None
        errors.loneElement shouldBe LexError("a", Some(1), Some(4))
        errors.loneElement.message shouldBe "Unexpected character 'a' at line 1, column 4"
      case Result.Success(_, lexemes) => fail(s"expected a failure, got ${lexemes.size.toString} lexemes")
  }

  test("getOrThrow throws the lexer errors as a LexerException") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    val exception = intercept[LexerException](Lexer.tokenize("123abc").getOrThrow)
    exception.errors shouldBe List(LexError("a", Some(1), Some(4)))
    exception.getMessage shouldBe "Unexpected character 'a' at line 1, column 4"
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

    val lexemes = Lexer.tokenize("(x + 42) * y - 1").getOrThrow

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
      """Token "ALPHABETIC" can never match: every input it matches is also matched by "IDENTIFIER",
        |which is defined earlier, so it always wins.
        |Declare "ALPHABETIC" ("[a-zA-Z]+") before "IDENTIFIER" ("[a-zA-Z_][a-zA-Z0-9_]*").""".stripMargin
  }

  test("cross-case pattern whose prefix an earlier pattern matches stays reachable (longest match)") {
    val Lexer = lexer:
      case "a" => Token["A"]
      case "ab" => Token["AB"]
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("ab a").getOrThrow
    assert(lexemes.map(_.name) == List("AB", "A"))
  }

  test("keyword declared before identifier: longest match keeps identifiers whole") {
    val Lexer = lexer:
      case "if" => Token["IF"]
      case id @ "[a-z]+" => Token["ID"](id)
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("if iffy").getOrThrow
    assert(lexemes.map(_.name) == List("IF", "ID"))
  }

  test("pattern covered by the union of earlier patterns names all of them") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "[a-m]" => Token["LOW"]
        case "[n-z]" => Token["HIGH"]
        case "[a-z]" => Token["ANY"]
      """).loneElement.message shouldBe
      """Token "ANY" can never match: every input it matches is also matched by "LOW" or "HIGH",
        |which are defined earlier, so one of them always wins.
        |"ANY" is redundant: remove it, or narrow "LOW" and "HIGH" so they no longer cover it.""".stripMargin
  }

  test("pattern with the same language as an earlier one is reported as a duplicate to remove") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "a|b" => Token["AB"]
        case "b|a" => Token["BA"]
      """).loneElement.message shouldBe
      """Token "BA" can never match: every input it matches is also matched by "AB",
        |which is defined earlier, so it always wins.
        |"AB" and "BA" match exactly the same inputs; remove one of them.""".stripMargin
  }

  test("within-case alternatives where one is a prefix of another both stay reachable - longer first") {
    val Lexer = lexer:
      case ">=" | ">" => Token["GREATER"]
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize(">= >").getOrThrow
    assert(lexemes.map(_.shape.fields("text")) == List(">=", ">"))
  }

  test("within-case alternatives where one is a prefix of another both stay reachable - shorter first") {
    val Lexer = lexer:
      case ">" | ">=" => Token["GREATER"]
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize(">= >").getOrThrow
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
      """Token name "X" is defined 2 times. Combine the patterns into a single case using alternatives: """ +
      """case "a" | "b" => ..."""
  }

  test("duplicate token name suggestion quotes patterns as Scala string literals") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "\\+" => Token["OP"]
        case "-" => Token["OP"]
      """).loneElement.message should endWith("""case "\\+" | "-" => ...""")
  }

  test("unexpected non-printable character is shown escaped") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    val exception = intercept[LexerException](Lexer.tokenize("1\t").getOrThrow)
    exception.getMessage shouldBe """Unexpected character '\t' at line 1, column 2"""
  }

  test("shadowing error escapes non-printable token names and patterns") {
    // the token name and the patterns hold a real tab, not the regex escape `\t`
    typeCheckErrors(
      "val Lexer = lexer:\n" + "  case \" |\t\" => Token[\"\t\"]\n" + "  case \"\t\" => Token[\"TAB\"]\n",
    ).loneElement.message shouldBe """Token "TAB" can never match: every input it matches is also matched by "\t",
                                     |which is defined earlier, so it always wins.
                                     |Declare "TAB" ("\t") before "\t" (" |\t").""".stripMargin
  }

  test("duplicate token name error escapes non-printable token names and patterns") {
    typeCheckErrors(
      "val Lexer = lexer:\n" + "  case \"\t\" => Token[\"\t\"]\n" + "  case \" \" => Token[\"\t\"]\n",
    ).loneElement.message shouldBe
      """Token name "\t" is defined 2 times. Combine the patterns into a single case using alternatives: """ +
      """case "\t" | " " => ..."""
  }

  test("invalid regex error escapes non-printable token names") {
    typeCheckErrors(
      "val Lexer = lexer:\n" + "  case \"(\" => Token[\"\t\"]\n",
    ).loneElement.message should startWith("""Invalid regex pattern for token "\t": """)
    typeCheckErrors(
      "val Lexer = lexer:\n" + "  case x @ (\"a\" | \"(\t\") => Token[x.type]\n",
    ).loneElement.message should startWith("""Invalid regex pattern for token "(\t": """)
  }

  test("track line and position across newlines") {
    val Lexer = lexer:
      case id @ "[a-zA-Z]+" => Token["IDENTIFIER"](id)
      case "\\s+" => Token.Ignored

    val lexemesResult = Lexer.tokenize("abc\ndef")

    val ctx = lexemesResult.ctx

    val lexemes = lexemesResult.getOrThrow
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
      val lexemes = Lexer.tokenize(reader).getOrThrow

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
