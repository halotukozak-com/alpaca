package bench.alpaca

import halotukozak.alpaca.{lexer, ErrorHandling, LexError, LexerCtx, Token}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Random

// The strategy is read from a mutable var (set in @Setup from the @Param string below)
// rather than fixed at compile time, so a single lexer definition can be reused across
// all three ErrorHandling.Strategy variants.
private var currentStrategy: ErrorHandling.Strategy = ErrorHandling.Strategy.Stop

private given ErrorHandling[LexerCtx.Default, LexError] = (_, _) => currentStrategy

private val ErrorRecoveryLexer = lexer[LexerCtx.Default] {
  case "\\s+" => Token.Ignored
  case x @ """[a-zA-Z_][a-zA-Z0-9_]*""" => Token["identifier"](x)
  case x @ """\d+""" => Token["number"](x.toInt)
}

private object ErrorRecoveryInputs:
  // Not matched by ErrorRecoveryLexer's rules, so it always triggers the error path.
  private val invalidChar = '~'

  private val validUnits = Vector("identifier42", "42", "anotherName", "100", "value7")

  /**
   * Builds an input of roughly `targetSize` chars, with `errorRatePercent`% of positions
   * replaced by an unmatched character, interspersed between otherwise-valid tokens.
   */
  def build(targetSize: Int, errorRatePercent: Int): String =
    val rnd = Random(seed = 42)
    val builder = new StringBuilder(targetSize + 32)
    var i = 0
    while builder.length < targetSize do
      if rnd.nextInt(100) < errorRatePercent then builder.append(invalidChar)
      else builder.append(validUnits(i % validUnits.length))
      builder.append(' ')
      i += 1
    builder.toString

/**
 * JMH benchmark comparing the cost of each [[ErrorHandling.Strategy]] across different
 * error rates.
 *
 * `Throw` and `Stop` terminate at the first unmatched character, so at any error rate
 * above 0 they measure "cost to reach and handle the first error" rather than full-input
 * throughput -- this is intentional, it's the actual cost difference between strategies
 * this benchmark is meant to surface. `SkipOne` and `SkipToNextMatch` always process the
 * full input, recovering from every error along the way.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(1)
class LexerErrorRecoveryBenchmark:

  @Param(Array("0", "1", "5", "10"))
  var errorRatePercent: String = uninitialized

  @Param(Array("SkipOne", "SkipToNextMatch", "Stop"))
  var strategyName: String = uninitialized

  @Param(Array("20000"))
  var size: String = uninitialized

  private var input: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    input = ErrorRecoveryInputs.build(size.toInt, errorRatePercent.toInt)

  @Setup(Level.Invocation)
  def selectStrategy(): Unit =
    currentStrategy = strategyName match
      case "SkipOne" => ErrorHandling.Strategy.SkipOne
      case "SkipToNextMatch" => ErrorHandling.Strategy.SkipToNextMatch
      case "Stop" => ErrorHandling.Strategy.Stop
      case other => throw IllegalArgumentException(s"Unknown strategy: $other")

  @Benchmark
  def tokenize(bh: Blackhole): Unit =
    try bh.consume(ErrorRecoveryLexer.tokenize(input))
    catch case _: RuntimeException => bh.consume("threw")
