package bench.alpaca

import halotukozak.alpaca.{lexer, LexerCtx, Token}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

private val SelectDynamicLexer = lexer[LexerCtx.Default] {
  case "\\s+" => Token.Ignored
  case x @ ("\\{" | "\\}" | "\\[" | "\\]" | "\\(" | "\\)" | ":" | "," | ";" | "\\." | "\\+" | "-" | "\\*" | "/") =>
    Token[x.type]
  case x @ """[a-zA-Z_][a-zA-Z0-9_]*""" => Token["identifier"](x)
  case x @ """\d+""" => Token["number"](x.toInt)
}

/** Cost of looking a token up by name (`myLexer.PLUS`), which the generated `selectDynamic` backs. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class SelectDynamicBenchmark:

  @Param(Array("identifier", "number", "$div"))
  var name: String = uninitialized

  @Benchmark
  def selectDynamic(bh: Blackhole): Unit =
    bh.consume(SelectDynamicLexer.selectDynamic(name))
