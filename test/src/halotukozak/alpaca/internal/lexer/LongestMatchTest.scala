package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.{lexer, LexerException, Token}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

// Pins the runtime token selection rule: at each position the lexer takes the longest match, and
// only a tie in length is decided by declaration order (earlier case wins). This replaced the
// first-match alternation of java.util.regex in 0.2.0 without any test noticing, hence this suite.
final class LongestMatchTest extends AnyFunSuite with Matchers:

  extension (lexemes: List[Lexeme[?, ?]]) private def tokens: List[(String, String)] = lexemes.map(l => (l.name, l.text))

  test("a longer match wins over an earlier declared shorter one") {
    val Lexer = lexer:
      case "=" => Token["ASSIGN"]
      case "==" => Token["EQ"]

    val lexemes = Lexer.tokenize("===").getOrThrow
    lexemes.tokens shouldBe List(("EQ", "=="), ("ASSIGN", "="))
  }

  test("a longer match wins even when an earlier pattern also matches a prefix of it") {
    val Lexer = lexer:
      case int @ "[0-9]+" => Token["INT"](int.toInt)
      case float @ "[0-9]+\\.[0-9]+" => Token["FLOAT"](float.toDouble)
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("15 1.5").getOrThrow
    lexemes.tokens shouldBe List(("INT", "15"), ("FLOAT", "1.5"))
  }

  test("declaration order decides only ties in length") {
    val Lexer = lexer:
      case id @ "[a-z]+" => Token["ID"](id)
      case hex @ "[0-9a-f]+" => Token["HEX"](hex)
      case "\\s+" => Token.Ignored

    // "fade" ties (both match 4 chars), so the earlier ID wins; "fade1" is longer only for HEX.
    val lexemes = Lexer.tokenize("fade fade1 0a").getOrThrow
    lexemes.tokens shouldBe List(("ID", "fade"), ("HEX", "fade1"), ("HEX", "0a"))
  }

  test("a keyword declared before the identifier pattern wins only the exact tie") {
    val Lexer = lexer:
      case "if" => Token["IF"]
      case id @ "[a-z]+" => Token["ID"](id)
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("if iffy i").getOrThrow
    lexemes.tokens shouldBe List(("IF", "if"), ("ID", "iffy"), ("ID", "i"))
  }

  test("ignored patterns take part in longest match like any other token") {
    val Lexer = lexer:
      case "/" => Token["DIV"]
      case num @ "[0-9]+" => Token["NUM"](num.toInt)
      case "//[^\n]*" => Token.Ignored
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("6/2 // halve\n1").getOrThrow
    lexemes.tokens shouldBe List(("NUM", "6"), ("DIV", "/"), ("NUM", "2"), ("NUM", "1"))
  }

  test("the longest match is taken without looking at what follows it") {
    val Lexer = lexer:
      case num @ "-?[0-9]+" => Token["NUM"](num.toInt)
      case "-" => Token["MINUS"]
      case "\\s+" => Token.Ignored

    // Maximal munch: "-2" is longer than "-", so "1-2" is two numbers, not a subtraction.
    Lexer.tokenize("1-2").getOrThrow.tokens shouldBe List(("NUM", "1"), ("NUM", "-2"))
    Lexer.tokenize("1 - 2").getOrThrow.tokens shouldBe List(("NUM", "1"), ("MINUS", "-"), ("NUM", "2"))
  }

  test("a match is never shortened to let the rest of the input tokenize") {
    val Lexer = lexer:
      case "a" => Token["A"]
      case "ab" => Token["AB"]
      case "bc" => Token["BC"]

    // "a" + "bc" would cover the input, but the lexer commits to the longest "ab" and gets stuck on "c".
    val exception = intercept[LexerException](Lexer.tokenize("abc").getOrThrow)
    exception.getMessage shouldBe "Unexpected character 'c' at line 1, column 3"
  }

  test("a single pattern matches as much as it can, across what look like separate tokens") {
    val Lexer = lexer:
      case "/\\*.*\\*/" => Token["COMMENT"]
      case id @ "[a-z]+" => Token["ID"](id)
      case "\\s+" => Token.Ignored

    val lexemes = Lexer.tokenize("/* a */ x /* b */").getOrThrow
    lexemes.tokens shouldBe List(("COMMENT", "/* a */ x /* b */"))
  }
