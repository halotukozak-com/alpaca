package halotukozak.userland

import halotukozak.alpaca.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ResultTest extends AnyFunSuite with Matchers:

  private final case class MyError(code: Int)

  private val success: Result[String, Int, MyError] = Result.Success("ctx", 1)
  private val failure: Result[String, Int, MyError] = Result.Failure("ctx", Some(1), ::(MyError(1), List(MyError(2))))

  test("a success gives its value") {
    success.ctx shouldBe "ctx"
    success.toOption shouldBe Some(1)
    success.toEither shouldBe Right(1)
  }

  test("a failure gives its errors, and a recovered value only by matching") {
    failure.ctx shouldBe "ctx"
    failure.toOption shouldBe None
    failure.toEither shouldBe Left(List(MyError(1), MyError(2)))
    failure match
      case Result.Failure(_, recovered, _) => recovered shouldBe Some(1)
      case Result.Success(_, _) => fail("expected a failure")
  }

  test("getOrThrow gives a parse's value, or throws its errors as a ParserException") {
    val SumLexer = lexer:
      case "\\+" => Token["+"]
      case number @ "[0-9]+" => Token["Num"](number.toInt)

    object SumParser extends Parser:
      val root: Rule[Int] = rule:
        case (SumLexer.Num(left), SumLexer.`+`(_), SumLexer.Num(right)) => left.value + right.value

    SumParser.parse(SumLexer.tokenize("1+2").getOrThrow).getOrThrow shouldBe 3

    SumParser.parse(SumLexer.tokenize("1+").getOrThrow) match
      case failure @ Result.Failure(_, _, errors) =>
        val exception = intercept[ParserException](failure.getOrThrow)
        exception.errors shouldBe errors
        exception.getMessage shouldBe errors.head.message
      case Result.Success(_, _) => fail("expected a failure")
  }
