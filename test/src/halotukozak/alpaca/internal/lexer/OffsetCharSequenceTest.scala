package halotukozak
package alpaca.internal.lexer

import halotukozak.alpaca.internal.lexer.OffsetCharSequence
import org.scalatest.funsuite.AnyFunSuite

final class OffsetCharSequenceTest extends AnyFunSuite:

  test("length returns correct value for fresh instance") {
    val ocs = OffsetCharSequence("hello")
    assert(ocs.length == 5)
  }

  test("length returns correct value after advancement") {
    val ocs = OffsetCharSequence("hello")
    ocs.from(2): Unit
    assert(ocs.length == 3)
  }

  test("charAt returns correct character at position") {
    val ocs = OffsetCharSequence("abcdef")
    assert(ocs.charAt(0) == 'a')
    assert(ocs.charAt(3) == 'd')
    assert(ocs.charAt(5) == 'f')
  }

  test("charAt returns correct character after advancement") {
    val ocs = OffsetCharSequence("abcdef")
    ocs.from(3): Unit
    assert(ocs.charAt(0) == 'd')
    assert(ocs.charAt(1) == 'e')
    assert(ocs.charAt(2) == 'f')
  }

  test("subSequence returns correct substring") {
    val ocs = OffsetCharSequence("abcdef")
    assert(ocs.subSequence(1, 4).toString == "bcd")
  }

  test("subSequence returns correct substring after advancement") {
    val ocs = OffsetCharSequence("abcdef")
    ocs.from(2): Unit
    assert(ocs.subSequence(0, 3).toString == "cde")
    assert(ocs.subSequence(1, 2).toString == "d")
  }

  test("from advances offset and returns this") {
    val ocs = OffsetCharSequence("abcdef")
    val result = ocs.from(3)
    assert(result eq ocs)
  }

  test("from called multiple times accumulates offset correctly") {
    val ocs = OffsetCharSequence("abcdefgh")
    ocs.from(2): Unit
    assert(ocs.charAt(0) == 'c')
    assert(ocs.length == 6)
    ocs.from(3): Unit
    assert(ocs.charAt(0) == 'f')
    assert(ocs.length == 3)
  }

  test("toString returns visible portion after advancement") {
    val ocs = OffsetCharSequence("hello world")
    ocs.from(6): Unit
    assert(ocs.toString == "world")
  }

  test("length equals zero after advancing past all content") {
    val ocs = OffsetCharSequence("abc")
    ocs.from(3): Unit
    assert(ocs.length == 0)
  }

  test("CharSequence contract: charAt(0) after from(3) on 'abcdef' returns 'd'") {
    val ocs = OffsetCharSequence("abcdef")
    ocs.from(3): Unit
    assert(ocs.charAt(0) == 'd')
  }
