package bench.alpaca

import halotukozak.alpaca.{lexer, LazyReader, LexerCtx, Token}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Using

// Same token set, defined twice against different context types so the JMH
// benchmark below can compare tokenization cost with (Default) and without
// (Empty) line/column tracking overhead.
private val ThroughputLexerDefault = lexer[LexerCtx.Default] {
  case "\\s+" => Token.Ignored
  case x @ ("\\{" | "\\}" | "\\[" | "\\]" | "\\(" | "\\)" | ":" | "," | ";" | "\\." | "\\+" | "-" | "\\*" | "/") =>
    Token[x.type]
  case x @ """[a-zA-Z_][a-zA-Z0-9_]*""" => Token["identifier"](x)
  case x @ """\d+""" => Token["number"](x.toInt)
}

private val ThroughputLexerEmpty = lexer[LexerCtx.Empty] {
  case "\\s+" => Token.Ignored
  case x @ ("\\{" | "\\}" | "\\[" | "\\]" | "\\(" | "\\)" | ":" | "," | ";" | "\\." | "\\+" | "-" | "\\*" | "/") =>
    Token[x.type]
  case x @ """[a-zA-Z_][a-zA-Z0-9_]*""" => Token["identifier"](x)
  case x @ """\d+""" => Token["number"](x.toInt)
}

private object ThroughputInputs:
  private val punctuationUnit = "{},:[]();.+-*/ "
  private val literalUnit = "myIdentifier123 anotherIdentifier 42 someValue100 thirdIdentifier "
  private val mixedUnit = "{key: value, list: [1, 2, three, four], nested: {inner: 5}}; "

  private def repeatToSize(unit: String, targetSize: Int): String =
    val builder = new StringBuilder(targetSize + unit.length)
    while builder.length < targetSize do builder.append(unit)
    builder.toString

  def forClass(inputClass: String, size: Int): String = inputClass match
    case "punctuationHeavy" => repeatToSize(punctuationUnit, size)
    case "literalHeavy" => repeatToSize(literalUnit, size)
    case "mixed" => repeatToSize(mixedUnit, size)
    case other => throw IllegalArgumentException(s"Unknown input class: $other")

/**
 * JMH benchmark isolating lexer throughput across different input shapes and
 * context types.
 *
 * Run with `-prof gc` to track allocations per operation, e.g. in CI nightly:
 * {{{
 * ./mill benchmarks.alpaca.runJmh LexerThroughputBenchmark -prof gc
 * }}}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(1)
class LexerThroughputBenchmark:

  @Param(Array("punctuationHeavy", "literalHeavy", "mixed"))
  var inputClass: String = uninitialized

  @Param(Array("50000"))
  var size: String = uninitialized

  private var input: String = uninitialized

  // the same input written to a file, for tokenizing through a LazyReader
  private var inputFile: Path = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    input = ThroughputInputs.forClass(inputClass, size.toInt)
    inputFile = Files.createTempFile("lexer-throughput", ".txt")
    Files.writeString(inputFile, input): Unit

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    Files.deleteIfExists(inputFile): Unit

  @Benchmark
  def tokenizeDefault(bh: Blackhole): Unit =
    bh.consume(ThroughputLexerDefault.tokenize(input))

  @Benchmark
  def tokenizeEmpty(bh: Blackhole): Unit =
    bh.consume(ThroughputLexerEmpty.tokenize(input))

  @Benchmark
  def tokenizeLazyReaderDefault(bh: Blackhole): Unit =
    Using.resource(LazyReader.from(inputFile)): reader =>
      bh.consume(ThroughputLexerDefault.tokenize(reader))
