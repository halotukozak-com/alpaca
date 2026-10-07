package halotukozak
package alpaca
package internal

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

private object GeneratedCodeGrammar:
  val Lexer = lexer:
    case " " => Token.Ignored
    case number @ "\\d+" => Token["NUMBER"](number.toInt)
    case "," => Token["COMMA"]
    case "-" => Token["MINUS"]
    case "#" => Token["HASH"]

  object NumbersParser extends Parser:
    val Number: Rule[Int] = rule:
      case (Lexer.MINUS.Option(minus), Lexer.NUMBER(n)) => if minus.isDefined then -n.value else n.value
    val root: Rule[Int] = rule(
      { case Number.SeparatedBy[Lexer.COMMA](numbers) => numbers.size },
      { case (Lexer.HASH(_), Number.List(numbers)) => numbers.sum },
    )

final class GeneratedCodeTest extends AnyFunSuite with Matchers:

  test("code generated for a lexer and a parser does not contain the grammar's source path") {
    val classDir =
      Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).resolve("halotukozak/alpaca/internal")
    val grammarClasses = Files
      .list(classDir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(name => name.startsWith("GeneratedCodeGrammar") && name.endsWith(".class"))
      .map(classDir.resolve)
      .toList
    val sourcePath = "halotukozak/alpaca/internal/GeneratedCodeTest.scala"

    grammarClasses should not be empty
    grammarClasses.filter(read(_).contains(sourcePath)) shouldBe empty
    grammarClasses.filter(read(_).contains("halotukozak/alpaca/internal/Source")) shouldBe empty
  }

  private def read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)
