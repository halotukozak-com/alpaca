package halotukozak
package alpaca.internal

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

final class JsonExportTest extends AnyFunSuite:

  test("wraps the written value in a version envelope") {
    val dir = Files.createTempDirectory("alpaca-export-test")
    given GrammarExportSettings = GrammarExportSettings(exportDirectory = Some(dir.toString))

    JsonExport.maybeWrite("Foo", "tokens", "hello")

    val written = Files.readString(dir.resolve("Foo.tokens.json"))
    assert(written == s"""{"version":${JsonExport.ExportFormatVersion},"context":"hello"}""")
  }

  test("skips rewriting the file when the content is unchanged") {
    val dir = Files.createTempDirectory("alpaca-export-test")
    given GrammarExportSettings = GrammarExportSettings(exportDirectory = Some(dir.toString))
    val path = dir.resolve("Foo.tokens.json")

    JsonExport.maybeWrite("Foo", "tokens", "hello")
    val firstWrite = Files.getLastModifiedTime(path)
    JsonExport.maybeWrite("Foo", "tokens", "hello")

    assert(Files.getLastModifiedTime(path) == firstWrite)
  }

  test("does nothing when no export directory is configured") {
    given GrammarExportSettings = GrammarExportSettings(exportDirectory = None)

    JsonExport.maybeWrite("Foo", "tokens", "hello") // must not throw
  }

  test("writes a source's file relative to its project root") {
    val project = Files.createTempDirectory("alpaca-export-project")
    Files.createDirectory(project.resolve(".git"))
    val sourceFile = project.resolve("src").resolve("Grammar.scala")
    val dir = Files.createTempDirectory("alpaca-export-test")
    given GrammarExportSettings = GrammarExportSettings(exportDirectory = Some(dir.toString))

    JsonExport.maybeWrite("Foo", "tokens", Source(line = 1, file = sourceFile.toString, start = 2, end = 3))

    val written = Files.readString(dir.resolve("Foo.tokens.json"))
    val context = """{"line":1,"file":"src/Grammar.scala","start":2,"end":3}"""
    assert(written == s"""{"version":${JsonExport.ExportFormatVersion},"context":$context}""")
  }

  test("keeps a source's file absolute outside any project root") {
    val sourceFile = Files.createTempDirectory("alpaca-export-no-project").resolve("Grammar.scala")

    assert(Source.exportPath(sourceFile.toString) == sourceFile.toAbsolutePath.normalize.toString)
  }
