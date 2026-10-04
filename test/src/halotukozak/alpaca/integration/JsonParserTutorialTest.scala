package halotukozak
package alpaca
package integration

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, rule, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite

import annotation.nowarn

/** Validates the json-parser tutorial code compiles and runs correctly. */
final class JsonParserTutorialTest extends AnyFunSuite:
  // === Section 1: Lexer (from tutorial) ===
  val JsonLexer = lexer:
    // Ignore whitespace
    case "\\s+" => Token.Ignored

    // Brackets and punctuation
    case "\\{" => Token["{"]
    case "\\}" => Token["}"]
    case "\\[" => Token["["]
    case "\\]" => Token["]"]
    case ":" => Token[":"]
    case "," => Token[","]

    // Literals
    case x @ ("false" | "true") => Token["Bool"](x.toBoolean)
    case "null" => Token["Null"](null: @nowarn("msg=unused explicit parameter"))

    // Numbers and strings
    case x @ """[-+]?\d+(\.\d+)?""" => Token["Number"](x.toDouble)
    case x @ """"(\\.|[^"])*"""" => Token["String"](x.slice(1, x.length - 1))

  // === Section 2: Parser (from tutorial) ===
  object JsonTutorialParser extends Parser:
    val root: Rule[Any] = rule:
      case Value(value) => value

    val Value: Rule[Any] = rule(
      { case JsonLexer.Null(n) => n.value },
      { case JsonLexer.Bool(b) => b.value },
      { case JsonLexer.Number(n) => n.value },
      { case JsonLexer.String(s) => s.value },
      { case Object(obj) => obj },
      { case Array(arr) => arr },
    )

    val Object: Rule[Map[String, Any]] = rule(
      { case (JsonLexer.`{`(_), JsonLexer.`}`(_)) => Map.empty[String, Any] },
      { case (JsonLexer.`{`(_), ObjectMembers(members), JsonLexer.`}`(_)) => members.toMap },
    )

    val ObjectMembers: Rule[List[(String, Any)]] = rule(
      { case ObjectMember(member) => scala.List(member) },
      { case (ObjectMembers(members), JsonLexer.`,`(_), ObjectMember(member)) => members :+ member },
    )

    val ObjectMember: Rule[(String, Any)] = rule:
      case (JsonLexer.String(s), JsonLexer.`:`(_), Value(v)) => (s.value, v)

    val Array: Rule[List[Any]] = rule(
      { case (JsonLexer.`[`(_), JsonLexer.`]`(_)) => Nil },
      { case (JsonLexer.`[`(_), ArrayElements(elems), JsonLexer.`]`(_)) => elems },
    )

    val ArrayElements: Rule[List[Any]] = rule(
      { case Value(v) => scala.List(v) },
      { case (ArrayElements(elems), JsonLexer.`,`(_), Value(v)) => elems :+ v },
    )

  // === Section 4: ModernJsonParser with EBNF operators ===
  object ModernJsonParser extends Parser:
    val root: Rule[Any] = rule:
      case Value(value) => value

    val Value: Rule[Any] = rule(
      { case JsonLexer.Null(n) => n.value },
      { case JsonLexer.Bool(b) => b.value },
      { case JsonLexer.Number(n) => n.value },
      { case JsonLexer.String(s) => s.value },
      { case Object(obj) => obj },
      { case Array(arr) => arr },
    )

    // .List on a Rule matches zero or more occurrences
    val Object: Rule[Map[String, Any]] = rule(
      { case (JsonLexer.`{`(_), JsonLexer.`}`(_)) => Map.empty[String, Any] },
      { case (JsonLexer.`{`(_), CommaSeparatedMembers(m), JsonLexer.`}`(_)) => m.toMap },
    )

    val CommaSeparatedMembers: Rule[List[(String, Any)]] = rule(
      { case ObjectMember(m) => scala.List(m) },
      { case (CommaSeparatedMembers(ms), JsonLexer.`,`(_), ObjectMember(m)) => ms :+ m },
    )

    val ObjectMember: Rule[(String, Any)] = rule:
      case (JsonLexer.String(s), JsonLexer.`:`(_), Value(v)) => (s.value, v)

    // .List on a Rule matches zero or more occurrences
    val Array: Rule[List[Any]] = rule(
      { case (JsonLexer.`[`(_), JsonLexer.`]`(_)) => Nil },
      { case (JsonLexer.`[`(_), CommaSeparatedValues(e), JsonLexer.`]`(_)) => e },
    )

    val CommaSeparatedValues: Rule[List[Any]] = rule(
      { case Value(v) => scala.List(v) },
      { case (CommaSeparatedValues(vs), JsonLexer.`,`(_), Value(v)) => vs :+ v },
    )

  // === Section 3: Tests ===
  test("parse simple JSON object") {
    val input =
      """
        |{
        |  "name": "John Doe",
        |  "age": 30,
        |  "isStudent": false,
        |  "courses": ["Math", "Science"],
        |  "address": {
        |    "city": "Anytown",
        |    "zip": "12345"
        |  },
        |  "nullValue": null
        |}
        |""".stripMargin

    val (_, lexemes) = JsonLexer.tokenize(input)
    val (_, result) = JsonTutorialParser.parse(lexemes)

    val expected = Map[String, Any](
      "name" -> "John Doe",
      "age" -> 30.0,
      "isStudent" -> false,
      "courses" -> List("Math", "Science"),
      "address" -> Map(
        "city" -> "Anytown",
        "zip" -> "12345",
      ),
      "nullValue" -> null,
    )
    assert(result == expected)
  }

  test("parse with ModernJsonParser") {
    val input = """{"a": 1, "b": [true, false, null]}"""
    val (_, lexemes) = JsonLexer.tokenize(input)
    val (_, result) = ModernJsonParser.parse(lexemes)

    val expected = Map[String, Any](
      "a" -> 1.0,
      "b" -> List[Any](true, false, null),
    )
    assert(result == expected)
  }

  test("parse empty object and array") {
    val (_, lexemes1) = JsonLexer.tokenize("{}")
    val (_, result1) = JsonTutorialParser.parse(lexemes1)
    assert(result1 == Map.empty)

    val (_, lexemes2) = JsonLexer.tokenize("[]")
    val (_, result2) = JsonTutorialParser.parse(lexemes2)
    assert(result2 == Nil)
  }

  test("parse primitives") {
    val (_, l1) = JsonLexer.tokenize("42")
    val (_, r1) = JsonTutorialParser.parse(l1)
    assert(r1 == 42.0)

    val (_, l2) = JsonLexer.tokenize("true")
    val (_, r2) = JsonTutorialParser.parse(l2)
    assert(r2 == true)

    val (_, l3) = JsonLexer.tokenize("null")
    val (_, r3) = JsonTutorialParser.parse(l3)
    assert(r3 == null)
  }
