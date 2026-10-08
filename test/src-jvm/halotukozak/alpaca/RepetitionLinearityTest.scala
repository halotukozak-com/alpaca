package halotukozak
package alpaca

import halotukozak.alpaca.internal.parser.Parser
import halotukozak.alpaca.{lexer, rule, ParserCtx, Rule, Token}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.management.ManagementFactory

final class RepetitionLinearityTest extends AnyFunSuite:

  val NumLexer = lexer:
    case " " => Token.Ignored
    case "," => Token["COMMA"]
    case number @ "\\d+" => Token["NUMBER"](number.toInt)

  case class Ctx() extends ParserCtx

  object ListParser extends Parser[Ctx]:
    val root: Rule[Int] = rule:
      case NumLexer.NUMBER.List(numbers) => numbers.size

  object SeparatedByParser extends Parser[Ctx]:
    val root: Rule[Int] = rule:
      case NumLexer.NUMBER.SeparatedBy[NumLexer.COMMA](items) => items.size

  // this thread's CPU time, so suites running in parallel don't skew the measurement
  private val threads = ManagementFactory.getThreadMXBean
  private def now(): Long =
    if threads.isCurrentThreadCpuTimeSupported then threads.getCurrentThreadCpuTime else System.nanoTime()

  private def bestParseNanos(parse: List[NumLexer.Lexeme] => Option[Int], input: String, expectedSize: Int): Long =
    val lexemes = NumLexer.tokenize(input).getOrThrow
    List
      .fill(5) {
        val start = now()
        val size = parse(lexemes)
        val elapsed = now() - start
        assert(size.contains(expectedSize))
        elapsed
      }
      .min

  private def assertLinear(parse: List[NumLexer.Lexeme] => Option[Int], separator: String, sizeOf: Int => Int) =
    val small = 20_000
    val large = 10 * small
    def input(n: Int) = Iterator.fill(n)("1").mkString(separator)
    val _ = bestParseNanos(parse, input(small), sizeOf(small)) // warm-up
    val _ = bestParseNanos(parse, input(large), sizeOf(large))
    val smallNanos = bestParseNanos(parse, input(small), sizeOf(small))
    val largeNanos = bestParseNanos(parse, input(large), sizeOf(large))
    // 10x the input: ~10x when linear, ~100x when quadratic
    assert(largeNanos.toDouble / smallNanos < 30.0)

  test("List parses in linear time") {
    assertLinear(ListParser.parse(_).toOption, " ", identity)
  }

  test("SeparatedBy parses in linear time") {
    assertLinear(SeparatedByParser.parse(_).toOption, ",", n => 2 * n - 1)
  }
