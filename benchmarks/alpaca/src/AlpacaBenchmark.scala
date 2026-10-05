package bench.alpaca

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.nio.file.{Files, Paths}
import scala.compiletime.uninitialized
import halotukozak.alpaca.*
import halotukozak.alpaca.bench.{MathLexer, MathParser}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(1)
class AlpacaBenchmark:

  @Param(
    Array(
      "iterative_math",
      "recursive_math",
      "iterative_json",
      "recursive_json",
      "big_grammar",
      "flat_list",
      "deep_nested",
    ),
  )
  var scenario: String = uninitialized

  @Param(Array("100", "500", "1000", "2000", "5000", "10000"))
  var size: String = uninitialized

  // Input text loaded from file (or synthesised in-memory for flat_list / deep_nested)
  private var input: String = uninitialized

  // Pre-built closures that call the correct lexer/parser for this scenario.
  // This avoids path-dependent type issues with Tokenization#Lexeme.
  private var lexFn: String => Any = uninitialized
  private var parseFn: String => Any = uninitialized
  private var fullParseFn: String => Any = uninitialized

  // Pre-tokenized input for pureParseOnly benchmark
  private var preTokenizedParseFn: () => Any = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    val n = size.toInt

    scenario match
      case "flat_list" =>
        // `n` tokens of `a` separated by spaces: stresses the LR reduction loop
        // on a single long left-recursive production.
        input = ("a " * n).trim
      case "deep_nested" =>
        // `n` levels of nested parens around a single `0`: stresses the parser's
        // recursive reduction chain.
        input = "(" * n + "0" + ")" * n
      case _ =>
        val fileName = s"${scenario}_$size.txt"
        // Walk up from CWD to find benchmarks/inputs/ — JMH fork CWD varies by Mill version
        val cwd = Paths.get("").toAbsolutePath
        val candidates = Iterator
          .iterate(cwd)(_.getParent)
          .takeWhile(_ != null)
          .take(5)
          .flatMap: dir =>
            Seq(dir.resolve(s"inputs/$fileName"), dir.resolve(s"benchmarks/inputs/$fileName"))
          .toSeq
        val inputPath = candidates
          .find(Files.exists(_))
          .getOrElse:
            sys.error(s"Input file not found for $scenario/$size. CWD=$cwd, tried: ${candidates.mkString(", ")}")
        input = new String(Files.readAllBytes(inputPath))

    scenario match {
      case s if s.contains("math") =>
        lexFn = (in: String) => MathLexer.tokenize(in)
        parseFn = (in: String) =>
          val tokens = MathLexer.tokenize(in).getOrThrow
          MathParser.parse(tokens).getOrThrow
        fullParseFn = (in: String) =>
          val tokens = MathLexer.tokenize(in).getOrThrow
          MathParser.parse(tokens).getOrThrow
        val preTokenized = MathLexer.tokenize(input).getOrThrow
        preTokenizedParseFn = () => MathParser.parse(preTokenized).getOrThrow

      case s if s.contains("json") =>
        lexFn = (in: String) => JsonLexer.tokenize(in)
        parseFn = (in: String) =>
          val tokens = JsonLexer.tokenize(in).getOrThrow
          JsonParser.parse(tokens).getOrThrow
        fullParseFn = (in: String) =>
          val tokens = JsonLexer.tokenize(in).getOrThrow
          JsonParser.parse(tokens).getOrThrow
        val preTokenized = JsonLexer.tokenize(input).getOrThrow
        preTokenizedParseFn = () => JsonParser.parse(preTokenized).getOrThrow

      case "big_grammar" =>
        lexFn = (in: String) => BigGrammarLexer.tokenize(in)
        parseFn = (in: String) =>
          val tokens = BigGrammarLexer.tokenize(in).getOrThrow
          BigGrammarParser.parse(tokens).getOrThrow
        fullParseFn = (in: String) =>
          val tokens = BigGrammarLexer.tokenize(in).getOrThrow
          BigGrammarParser.parse(tokens).getOrThrow
        val preTokenized = BigGrammarLexer.tokenize(input).getOrThrow
        preTokenizedParseFn = () => BigGrammarParser.parse(preTokenized).getOrThrow

      case "flat_list" =>
        lexFn = (in: String) => FlatListLexer.tokenize(in)
        parseFn = (in: String) =>
          val tokens = FlatListLexer.tokenize(in).getOrThrow
          FlatListParser.parse(tokens).getOrThrow
        fullParseFn = (in: String) =>
          val tokens = FlatListLexer.tokenize(in).getOrThrow
          FlatListParser.parse(tokens).getOrThrow
        val preTokenized = FlatListLexer.tokenize(input).getOrThrow
        preTokenizedParseFn = () => FlatListParser.parse(preTokenized).getOrThrow

      case "deep_nested" =>
        lexFn = (in: String) => DeepNestedLexer.tokenize(in)
        parseFn = (in: String) =>
          val tokens = DeepNestedLexer.tokenize(in).getOrThrow
          DeepNestedParser.parse(tokens).getOrThrow
        fullParseFn = (in: String) =>
          val tokens = DeepNestedLexer.tokenize(in).getOrThrow
          DeepNestedParser.parse(tokens).getOrThrow
        val preTokenized = DeepNestedLexer.tokenize(input).getOrThrow
        preTokenizedParseFn = () => DeepNestedParser.parse(preTokenized).getOrThrow
    }
  }

  @Benchmark
  def lex(bh: Blackhole): Unit =
    try bh.consume(lexFn(input))
    catch case _: StackOverflowError => bh.consume("StackOverflowError")

  @Benchmark
  def parseOnly(bh: Blackhole): Unit =
    try bh.consume(parseFn(input))
    catch case _: StackOverflowError => bh.consume("StackOverflowError")

  @Benchmark
  def fullParse(bh: Blackhole): Unit =
    try bh.consume(fullParseFn(input))
    catch case _: StackOverflowError => bh.consume("StackOverflowError")

  @Benchmark
  def pureParseOnly(bh: Blackhole): Unit =
    try bh.consume(preTokenizedParseFn())
    catch case _: StackOverflowError => bh.consume("StackOverflowError")
