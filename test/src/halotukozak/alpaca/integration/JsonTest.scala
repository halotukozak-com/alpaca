package halotukozak
package alpaca
package integration

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, rule, withLazyReader, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite

import annotation.nowarn

final class JsonTest extends AnyFunSuite:
  test("e2e json test") {
    val JsonLexer = lexer:
      // ignoring whitespaces
      case "\\s+" => Token.Ignored

      // brackets and punctuation marks
      case "\\{" => Token["{"]
      case "\\}" => Token["}"]
      case "\\[" => Token["["]
      case "\\]" => Token["]"]
      case ":" => Token[":"]
      case "," => Token[","]

      // literals
      case x @ ("false" | "true") => Token["Bool"](x.toBoolean)
      case "null" => Token["Null"](null: @nowarn("msg=unused explicit parameter")) // todo: why needs @nowarn?

      // numbers and strings
      case x @ """[-+]?\d+(\.\d+)?""" => Token["Number"](x.toDouble)
      case x @ """"(\\.|[^"])*"""" => Token["String"](x.slice(1, x.length - 1))

    object JsonE2EParser extends Parser:
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

    withLazyReader("true"): input =>
      val (_, lexemes) = JsonLexer.tokenize(input)
      val (_, result) = JsonE2EParser.parse(lexemes)
      assert(result == true)

    withLazyReader("""
      {
        "name": "John Doe",
        "age": 30,
        "isStudent": false,
        "courses": ["Math", "Science", "History"],
        "address": {
          "street": "123 Main St",
          "city": "Anytown",
          "zip": "12345"
        },
        "scores": [95.5, 88.0, 76.5],
        "nullValue": null
      }
      """) { input =>
      val (_, lexemes) = JsonLexer.tokenize(input)
      val (_, result) = JsonE2EParser.parse(lexemes)

      val expected = Map[String, Any](
        "name" -> "John Doe",
        "age" -> 30.0,
        "isStudent" -> false,
        "courses" -> List("Math", "Science", "History"),
        "address" -> Map(
          "street" -> "123 Main St",
          "city" -> "Anytown",
          "zip" -> "12345",
        ),
        "scores" -> List(95.5, 88.0, 76.5),
        "nullValue" -> null,
      )

      assert(result == expected)
    }

    withLazyReader("""
      [
        {
          "id": 1,
          "name": "Alice"
        },
        {
          "id": 2,
          "name": "Bob"
        },
        {
          "id": 3,
          "name": "Charlie"
        }
      ]
      """) { input =>
      val (_, lexemes) = JsonLexer.tokenize(input)
      val (_, result) = JsonE2EParser.parse(lexemes)

      val expected = List(
        Map[String, Any]("id" -> 1.0, "name" -> "Alice"),
        Map[String, Any]("id" -> 2.0, "name" -> "Bob"),
        Map[String, Any]("id" -> 3.0, "name" -> "Charlie"),
      )

      assert(result == expected)
    }

    withLazyReader("""
      {
        "menu": {
          "id": "file",
          "value": "File",
          "popup": {
            "menuitem": [
              {"value": "New", "onclick": "CreateNewDoc()"},
              {"value": "Open", "onclick": "OpenDoc()"},
              {"value": "Close", "onclick": "CloseDoc()"}
            ]
          }
        }
      }
      """) { input =>
      val (_, lexemes) = JsonLexer.tokenize(input)
      val (_, result) = JsonE2EParser.parse(lexemes)

      val expected = Map(
        "menu" -> Map[String, Any](
          "id" -> "file",
          "value" -> "File",
          "popup" -> Map(
            "menuitem" -> List(
              Map("value" -> "New", "onclick" -> "CreateNewDoc()"),
              Map("value" -> "Open", "onclick" -> "OpenDoc()"),
              Map("value" -> "Close", "onclick" -> "CloseDoc()"),
            ),
          ),
        ),
      )

      assert(result == expected)
    }
  }
