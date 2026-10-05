package bench.alpaca

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, parse, rule, Rule, Token}

val JsonLexer = lexer {
  // ignoring whitespaces
  case "\\s+" => Token.Ignored

  // brackets and punctuation marks
  case x @ ("\\{" | "\\}" | "\\[" | "\\]" | ":" | ",") => Token[x.type]

  // literals
  case x @ ("false" | "true") => Token["Bool"](x.toBoolean)
  case "null" => Token["Null"](null)

  // numbers and strings
  case x @ """[-+]?\d+(\.\d+)?""" => Token["Number"](x.toDouble)
  case x @ """"(\\.|[^"])*"""" => Token["String"](x.slice(1, x.length - 1))
}

object JsonParser extends Parser:
  val root: Rule[Any] = rule { case Value(value) => value }

  val Value: Rule[Any] = rule(
    { case JsonLexer.Null(n) => n.value },
    { case JsonLexer.Bool(b) => b.value },
    { case JsonLexer.Number(n) => n.value },
    { case JsonLexer.String(s) => s.value },
    { case Object(obj) => obj },
    { case Array(arr) => arr },
  )

  val Object: Rule[Map[String, Any]] = rule(
    { case (JsonLexer.`\\{`(_), JsonLexer.`\\}`(_)) => Map.empty[String, Any] },
    { case (JsonLexer.`\\{`(_), ObjectMembers(members), JsonLexer.`\\}`(_)) =>
      members.toMap
    },
  )

  val ObjectMembers: Rule[List[(String, Any)]] = rule(
    { case ObjectMember(member) => scala.List(member) },
    { case (ObjectMembers(members), JsonLexer.`,`(_), ObjectMember(member)) =>
      members :+ member
    },
  )

  val ObjectMember: Rule[(String, Any)] = rule { case (JsonLexer.String(s), JsonLexer.`:`(_), Value(v)) =>
    (s.value, v)
  }

  val Array: Rule[List[Any]] = rule(
    { case (JsonLexer.`\\[`(_), JsonLexer.`\\]`(_)) => Nil },
    { case (JsonLexer.`\\[`(_), ArrayElements(elems), JsonLexer.`\\]`(_)) =>
      elems
    },
  )

  val ArrayElements: Rule[List[Any]] = rule(
    { case Value(v) => scala.List(v) },
    { case (ArrayElements(elems), JsonLexer.`,`(_), Value(v)) => elems :+ v },
  )

@main def jsonParserMain(): Unit = {
  import java.nio.file.{Files, Paths}

  val filePathIterative = s"inputs/iterative_json_3.txt"
  val fileContentIterative = new String(
    Files.readAllBytes(Paths.get(filePathIterative)),
  )

  try
    val (_, tokens) = JsonLexer.tokenize(fileContentIterative)
    val result = JsonParser.parse(tokens).getOrThrow
    println(s"\nResult Iterative: $result")
  catch
    case e: Exception =>
      println(s"\nError Iterative: ${e.getMessage}")
      e.printStackTrace()

  val filePathRecursive = s"inputs/recursive_json_3.txt"
  val fileContentRecursive = new String(
    Files.readAllBytes(Paths.get(filePathRecursive)),
  )

  try
    val (_, tokens) = JsonLexer.tokenize(fileContentRecursive)
    val result = JsonParser.parse(tokens).getOrThrow
    println(s"\nResult Recursive: $result")
  catch
    case e: Exception =>
      println(s"\nError Recursive: ${e.getMessage}")
      e.printStackTrace()
}
