package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.Lexeme
import halotukozak.alpaca.{lexer, withLazyReader, LexerError, LexerException, Result, Token}
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

  private def fields(text: String, column: Int, line: Int): Map[String, Any] =
    Map[String, Any]("text" -> text, "column" -> column, "line" -> line)

  test("selectDynamic returns ctx fields and throws for missing keys") {
    val lexeme: Lexeme[?, ?] =
      new Lexeme("IDENTIFIER", "hello", "hello", Array("column", "line"), Array(1, 1))

    lexeme.text shouldBe "hello"
    lexeme.selectDynamic("column") shouldBe 1
    lexeme.selectDynamic("line") shouldBe 1
    intercept[NoSuchElementException](lexeme.selectDynamic("missing"))
  }

  test("tokenize simple identifier") {
    val Lexer = lexer:
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)

    val lexemes = Lexer.tokenize("hello").getOrThrow
    assert(lexemes.map(_.shape) == List[Shape](("IDENTIFIER", "hello", fields("hello", 1, 1))))
  }

  test("tokenize with whitespace ignored") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number)
      case "\\+" => Token["PLUS"]
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("42 + 13").getOrThrow

    assert(
      lexemes.map(_.shape) == List[Shape](
        ("NUMBER", "42", fields("42", 1, 1)),
        ("PLUS", (), fields("+", 4, 1)),
        ("NUMBER", "13", fields("13", 6, 1)),
      ),
    )
  }

  test("tokenize empty string") {
    val Lexer = lexer:
      case id @ "[a-zA-Z][a-zA-Z0-9]*" => Token["IDENTIFIER"](id)

    val lexemes = Lexer.tokenize("").getOrThrow
    assert(lexemes == Nil)
  }

  test("unexpected character fails with a LexerError and the lexemes before it are not recovered") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    Lexer.tokenize("123abc") match
      case Result.Failure(_, recovered, errors) =>
        recovered shouldBe None
        val error = errors.loneElement
        (error.unexpected, error.line, error.column) shouldBe ("a", 1, 4)
        error.message shouldBe "Unexpected character 'a'"
      case Result.Success(_, lexemes) => fail(s"expected a failure, got ${lexemes.size.toString} lexemes")
  }

  test("getOrThrow throws the lexer errors as a LexerException") {
    val Lexer = lexer:
      case number @ "[0-9]+" => Token["NUMBER"](number.toInt)

    val exception = intercept[LexerException](Lexer.tokenize("123abc").getOrThrow)
    exception.errors.map(e => (e.unexpected, e.selectDynamic("line"), e.selectDynamic("column"))) shouldBe
      List(("a", 1, 4))
    exception.getMessage shouldBe "Unexpected character 'a'"
  }

  test("LexerError compares, hashes and prints the unexpected input with the context fields") {
    def error(names: String*)(values: Any*) = new LexerError("a", names.toArray, values.toArray)

    error("column", "line")(4, 1) shouldBe error("column", "line")(4, 1)
    error("column", "line")(4, 1).hashCode shouldBe error("column", "line")(4, 1).hashCode
    error("column", "line")(4, 1) should not be error("column", "line")(5, 1)
    error("column", "line")(4, 1) should not be error("col", "line")(4, 1)
    error("column", "line")(4, 1).toString shouldBe "LexerError(a, column = 4, line = 1)"
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
        ("LPAREN", (), fields("(", 1, 1)),
        ("IDENTIFIER", "x", fields("x", 2, 1)),
        ("PLUS", (), fields("+", 4, 1)),
        ("NUMBER", "42", fields("42", 6, 1)),
        ("RPAREN", (), fields(")", 8, 1)),
        ("MULTIPLY", (), fields("*", 10, 1)),
        ("IDENTIFIER", "y", fields("y", 12, 1)),
        ("MINUS", (), fields("-", 14, 1)),
        ("NUMBER", "1", fields("1", 16, 1)),
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

  test("every shadowed token is reported at its rule, in source order") {
    // typeCheckErrors lists the errors latest first
    val errors = typeCheckErrors("""
      val Lexer = lexer:
        case "[a-z]+" => Token["ID"]
        case "[0-9]+" => Token["NUM"]
        case "if" => Token["IF"]
        case "42" => Token["ANSWER"]
        case "else" => Token["ELSE"]
      """).reverse
    errors.map(_.message.linesIterator.next()) shouldBe List(
      """Token "IF" can never match: every input it matches is also matched by "ID",""",
      """Token "ANSWER" can never match: every input it matches is also matched by "NUM",""",
      """Token "ELSE" can never match: every input it matches is also matched by "ID",""",
    )
    errors.map(_.lineContent.trim) shouldBe List(
      """case "if" => Token["IF"]""",
      """case "42" => Token["ANSWER"]""",
      """case "else" => Token["ELSE"]""",
    )
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

  test("lookahead in a pattern is not supported yet") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "a(?=b)" => Token["A"]
      """).loneElement.message shouldBe
      """Token "A" uses a lookahead `(?=...)` ("a(?=b)"), which is not supported yet"""
  }

  test("negative lookahead in a pattern is not supported yet") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "a(?!b)" => Token["A"]
      """).loneElement.message shouldBe
      """Token "A" uses a negative lookahead `(?!...)` ("a(?!b)"), which is not supported yet"""
  }

  test("start anchor in a pattern is not supported yet") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "^a" => Token["A"]
      """).loneElement.message shouldBe
      """Token "A" uses a start anchor `^` or `\A` ("^a"), which is not supported yet"""
  }

  test("end anchors in patterns are not supported yet") {
    typeCheckErrors("""
      val Lexer = lexer:
        case "a$" => Token["A"]
        case "b\\z" => Token["B"]
      """).map(_.message) should contain theSameElementsAs List(
      """Token "A" uses an end anchor `$`, `\Z` or `\z` ("a$"), which is not supported yet""",
      """Token "B" uses an end anchor `$`, `\Z` or `\z` ("b\z"), which is not supported yet""",
    )
  }

  test("unsupported construct nested in a bound alternative names the offending alternative") {
    typeCheckErrors("""
      val Lexer = lexer:
        case x @ ("a" | "(b|^c)+") => Token[x.type]
      """).loneElement.message shouldBe
      """Token "(b|^c)+" uses a start anchor `^` or `\A` ("(b|^c)+"), which is not supported yet"""
  }

  test("a shadowed token keeps the lexer typed, so parser actions reading its lexemes compile") {
    typeCheckErrors("""
      import halotukozak.alpaca.{Parser, Rule, rule}

      val L = lexer:
        case name @ "[a-z]+" => Token["IDENT"](name)
        case "if" => Token["IF"]

      object P extends Parser:
        val root: Rule[String] = rule:
          case L.IDENT(name) => name.value
      """).loneElement.message should startWith("""Token "IF" can never match: """)
  }

  test("invalid regexes keep the lexer typed and are all reported") {
    typeCheckErrors("""
      import halotukozak.alpaca.{Parser, Rule, rule}

      val L = lexer:
        case name @ "\\b[a-z]+" => Token["VAR"](name)
        case "(" | "[0-9]+" | ")" => Token["NUM"]

      object P extends Parser:
        val root: Rule[String] = rule:
          case L.VAR(name) => name.value
      """).map(_.message.takeWhile(_ != ':')) should contain theSameElementsAs List(
      "Invalid regex pattern for token \"VAR\"",
      "Invalid regex pattern for token \"NUM\"",
      "Invalid regex pattern for token \"NUM\"",
    )
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

  test("every duplicate token name is reported at its redefinition, in source order") {
    // the names are chosen so that `HashMap` order differs from source order; typeCheckErrors lists the errors latest first
    val errors = typeCheckErrors("""
      val Lexer = lexer:
        case "z" => Token["Z"]
        case "a" => Token["A"]
        case "m" => Token["M"]
        case "y" => Token["Z"]
        case "b" => Token["A"]
        case "n" => Token["M"]
        case "c" => Token["A"]
      """).reverse
    errors.map(_.message) shouldBe List(
      """Token name "Z" is defined 2 times. Combine the patterns into a single case using alternatives: case "z" | "y" => ...""",
      """Token name "A" is defined 3 times. Combine the patterns into a single case using alternatives: case "a" | "b" | "c" => ...""",
      """Token name "M" is defined 2 times. Combine the patterns into a single case using alternatives: case "m" | "n" => ...""",
    )
    errors.map(_.lineContent.trim) shouldBe List(
      """case "y" => Token["Z"]""",
      """case "b" => Token["A"]""",
      """case "n" => Token["M"]""",
    )
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
    exception.getMessage shouldBe """Unexpected character '\t'"""
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

  test("track line and column across newlines") {
    val Lexer = lexer:
      case id @ "[a-zA-Z]+" => Token["IDENTIFIER"](id)
      case "\\s+" => Token.Ignored

    val lexed = Lexer.tokenize("abc\ndef")

    val lexemes = lexed.getOrThrow
    assert(
      lexemes.map(_.shape) == List[Shape](
        ("IDENTIFIER", "abc", fields("abc", 1, 1)),
        ("IDENTIFIER", "def", fields("def", 1, 2)),
      ),
    )
    lexed.ctx.line shouldBe 2
    lexed.ctx.column shouldBe 4
  }

  test("line and column count every newline inside a longer match, including \\r\\n") {
    val Lexer = lexer:
      case id @ "[a-zA-Z]+" => Token["IDENTIFIER"](id)
      case "\\s+" => Token.Ignored

    val lexed = Lexer.tokenize("abc \n\r\n  def\r\nghi  ")

    assert(
      lexed.getOrThrow.map(_.shape) == List[Shape](
        ("IDENTIFIER", "abc", fields("abc", 1, 1)),
        ("IDENTIFIER", "def", fields("def", 3, 3)),
        ("IDENTIFIER", "ghi", fields("ghi", 1, 4)),
      ),
    )
    lexed.ctx.line shouldBe 4
    lexed.ctx.column shouldBe 6
  }

  test("column after a multi-line match counts code points after its last newline") {
    val Lexer = lexer:
      case comment @ "/\\*[^*]*\\*/" => Token["COMMENT"](comment)
      case id @ "[a-z]+" => Token["IDENTIFIER"](id)

    val lexed = Lexer.tokenize("/*😀\nżółć😀*/x")

    assert(
      lexed.getOrThrow.map(_.shape) == List[Shape](
        ("COMMENT", "/*😀\nżółć😀*/", fields("/*😀\nżółć😀*/", 1, 1)),
        ("IDENTIFIER", "x", fields("x", 8, 2)),
      ),
    )
    lexed.ctx.line shouldBe 2
    lexed.ctx.column shouldBe 9
  }

  test("a pattern that can match the empty string is reported at its rule") {
    val error: scala.compiletime.testing.Error = typeCheckErrors("""
    val EmptyLexer = lexer:
      case "b" => Token["B"]
      case "a*" => Token["A"]
    """).loneElement
    error.message shouldBe
      """Token "A" can match the empty string ("a*"); a token must consume at least one character"""
    error.lineContent.trim shouldBe "case \"a*\" => Token[\"A\"]"
  }

  test("an ignored pattern that can match the empty string is reported at its rule") {
    val error: scala.compiletime.testing.Error = typeCheckErrors("""
    val EmptyLexer = lexer:
      case "b" => Token["B"]
      case "x" | " *" => Token.Ignored
    """).loneElement
    error.message shouldBe
      """Token " *" can match the empty string (" *"); a token must consume at least one character"""
    error.lineContent.trim shouldBe "case \"x\" | \" *\" => Token.Ignored"
  }

  test("selectDynamic with an unknown token name throws NoSuchElementException") {
    val Lexer = lexer:
      case "a" => Token["A"]

    Lexer.selectDynamic("A").info.name.raw shouldBe "A"
    intercept[NoSuchElementException](Lexer.selectDynamic("missing")).getMessage shouldBe "No token named \"missing\""
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
          ("LPAREN", (), fields("(", 1, 1)),
          ("IDENTIFIER", "x", fields("x", 2, 1)),
          ("PLUS", (), fields("+", 4, 1)),
          ("NUMBER", "42", fields("42", 6, 1)),
          ("RPAREN", (), fields(")", 8, 1)),
          ("MULTIPLY", (), fields("*", 10, 1)),
          ("IDENTIFIER", "y", fields("y", 12, 1)),
          ("MINUS", (), fields("-", 14, 1)),
          ("NUMBER", "1", fields("1", 16, 1)),
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
