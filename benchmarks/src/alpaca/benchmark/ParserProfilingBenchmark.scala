package alpaca.benchmark

import halotukozak.alpaca.internal.lexer.Lexeme
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.nio.file.{Files, Paths}
import scala.compiletime.uninitialized
import annotation.nowarn

// Lexer/parser definitions for profiling benchmarks.
// These mirror the definitions in RuntimeBenchmark.scala (which are file-private).

private val ProfilingJsonLexer = lexer:
  case "\\s+" => Token.Ignored
  case "\\{" => Token["{"]
  case "\\}" => Token["}"]
  case "\\[" => Token["["]
  case "\\]" => Token["]"]
  case ":" => Token[":"]
  case "," => Token[","]
  case x @ ("false" | "true") => Token["Bool"](x.toBoolean)
  case "null" => Token["Null"](null: @nowarn("msg=unused explicit parameter"))
  case x @ """[-+]?\d+(\.\d+)?""" => Token["Number"](x.toDouble)
  case x @ """"(\\.|[^"])*"""" => Token["String"](x.slice(1, x.length - 1))

private object ProfilingJsonParser extends Parser:
  val root: Rule[Any] = rule:
    case Value(value) => value

  val Value: Rule[Any] = rule(
    { case ProfilingJsonLexer.Null(n) => n.value },
    { case ProfilingJsonLexer.Bool(b) => b.value },
    { case ProfilingJsonLexer.Number(n) => n.value },
    { case ProfilingJsonLexer.String(s) => s.value },
    { case Object(obj) => obj },
    { case Array(arr) => arr },
  )

  val Object: Rule[Map[String, Any]] = rule(
    { case (ProfilingJsonLexer.`{`(_), ProfilingJsonLexer.`}`(_)) => Map.empty[String, Any] },
    { case (ProfilingJsonLexer.`{`(_), ObjectMembers(members), ProfilingJsonLexer.`}`(_)) => members.toMap },
  )

  val ObjectMembers: Rule[List[(String, Any)]] = rule(
    { case ObjectMember(member) => scala.List(member) },
    { case (ObjectMembers(members), ProfilingJsonLexer.`,`(_), ObjectMember(member)) => members :+ member },
  )

  val ObjectMember: Rule[(String, Any)] = rule:
    case (ProfilingJsonLexer.String(s), ProfilingJsonLexer.`:`(_), Value(v)) => (s.value, v)

  val Array: Rule[List[Any]] = rule(
    { case (ProfilingJsonLexer.`[`(_), ProfilingJsonLexer.`]`(_)) => Nil },
    { case (ProfilingJsonLexer.`[`(_), ArrayElements(elems), ProfilingJsonLexer.`]`(_)) => elems },
  )

  val ArrayElements: Rule[List[Any]] = rule(
    { case Value(v) => scala.List(v) },
    { case (ArrayElements(elems), ProfilingJsonLexer.`,`(_), Value(v)) => elems :+ v },
  )

/**
 * JMH benchmark that isolates parser performance from lexer performance.
 *
 * Pre-tokenizes input in @Setup so that @Benchmark methods measure only the
 * parse() call. Used for profiling with -prof gc and -prof stack to identify
 * parser allocation hotspots and CPU-hot methods.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(1)
class ParserProfilingBenchmark:

  @Param(Array("iterative_json", "recursive_json"))
  var scenario: String = uninitialized

  @Param(Array("2000"))
  var size: String = uninitialized

  // Pre-tokenized input stored from @Setup -- isolates parser from lexer
  private var tokens: List[ProfilingJsonLexer.Lexeme] = uninitialized

  // Also keep the input string for comparison benchmark
  private var input: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Walk up from working directory to find benchmarks/inputs/
    var dir = Paths.get(System.getProperty("user.dir"))
    while dir != null && !Files.exists(dir.resolve("benchmarks/inputs")) do dir = dir.getParent
    val inputsDir =
      if dir != null then dir.resolve("benchmarks/inputs")
      else Paths.get("benchmarks/inputs")

    val inputPath = inputsDir.resolve(s"${scenario}_$size.txt")
    input = new String(Files.readAllBytes(inputPath))

    // Pre-tokenize: this happens once in setup, not during measurement
    val toks = ProfilingJsonLexer.tokenize(input).getOrThrow
    tokens = toks
  }

  /** Pure parse benchmark -- measures only parser.parse() on pre-tokenized input. */
  @Benchmark
  def pureParseOnly(bh: Blackhole): Unit =
    try bh.consume(ProfilingJsonParser.parse(tokens).getOrThrow)
    catch case _: StackOverflowError => bh.consume("StackOverflowError")

  /** Lex+parse benchmark for comparison -- measures tokenize() + parse(). */
  @Benchmark
  def parseWithLex(bh: Blackhole): Unit =
    try
      val t = ProfilingJsonLexer.tokenize(input).getOrThrow
      bh.consume(ProfilingJsonParser.parse(t).getOrThrow)
    catch case _: StackOverflowError => bh.consume("StackOverflowError")
