package halotukozak
package alpaca
package internal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * Guards the rule that macro errors go through [[error]] / [[errorAndAbort]] from `errors.scala`, whose position
 * argument is mandatory, so no error can silently land on the macro expansion site.
 */
final class ErrorReportingTest extends AnyFunSuite with Matchers:

  private val sources: Path = Paths.get(sys.env("MILL_WORKSPACE_ROOT"), "src")

  test("macro errors are reported only through internal error/errorAndAbort") {
    val direct = """report\s*\.\s*(error|errorAndAbort)\b""".r

    val offenders = Files
      .walk(sources)
      .iterator
      .asScala
      .filter(path => path.toString.endsWith(".scala") && path.getFileName.toString != "errors.scala")
      .flatMap: path =>
        Files
          .readAllLines(path)
          .asScala
          .zipWithIndex
          .collect:
            case (line, index) if direct.findFirstIn(line).isDefined => show"${sources.relativize(path)}:${index + 1}"
      .toList

    withClue("use halotukozak.alpaca.internal.error / errorAndAbort, which require a position:\n") {
      offenders shouldBe empty
    }
  }
