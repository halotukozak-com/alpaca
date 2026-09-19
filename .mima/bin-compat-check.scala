//> using scala 2.13
//> using dep com.typesafe::mima-core:1.2.1

// Binary-compatibility check between a released artifact and the current
// build, via MiMa's core API (there is no scala-cli MiMa plugin).
//
// Usage: scala-cli run .mima/bin-compat-check.scala -- <oldJar> <newJar> <sharedClasspath>
//   oldJar          the previously released library JAR
//   newJar          the freshly built library JAR (scala-cli package --library)
//   sharedClasspath pathSeparator-joined dependency classpath (scala3-library, deps, …)
//
// Only *backward* compatibility (can code compiled against oldJar still link
// against newJar) gates the exit code — that's the actual SemVer contract a
// same-major release makes. *Forward* is printed for context (it's what's new
// since oldJar) but always has "problems" whenever you add API, so it's never
// a reason to fail on its own.
//
// Exit code: 0 if backward-compatible, 1 otherwise.

import java.io.File
import com.typesafe.tools.mima.lib.MiMaLib
import com.typesafe.tools.mima.core.Problem

object BinCompatCheck {
  def main(args: Array[String]): Unit = {
    val Array(oldJar, newJar, sharedCp) = args
    val classpath = sharedCp.split(File.pathSeparator).iterator
      .filter(_.nonEmpty).map(new File(_)).toList

    def problems(prev: String, curr: String): List[Problem] =
      new MiMaLib(classpath).collectProblems(new File(prev), new File(curr), Nil)

    val backward = problems(oldJar, newJar)
    val forward  = problems(newJar, oldJar)

    def report(label: String, ps: List[Problem]): Unit =
      if (ps.isEmpty) println(s"[mima] $label: OK")
      else {
        println(s"[mima] $label: ${ps.size} problem(s)")
        ps.foreach(p => println(s"  - ${p.description("current")}"))
      }

    report("backward (code built against the release vs the new JAR)", backward)
    report("forward  (new API vs the release — expected to list additions)", forward)

    if (backward.nonEmpty) sys.exit(1)
  }
}
