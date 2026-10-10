package halotukozak
package alpaca.internal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class DuplicatesByTest extends AnyFunSuite with Matchers:

  test("duplicatesBy groups the elements sharing a key in the order the keys first appear") {
    List("z1", "a1", "m1", "z2", "q1", "a2", "m2", "a3").duplicatesBy(_.head) shouldBe List(
      List("z1", "z2"),
      List("a1", "a2", "a3"),
      List("m1", "m2"),
    )
  }

  test("duplicatesBy is empty when every key is unique") {
    List("a", "b", "c").duplicatesBy(identity) shouldBe Nil
  }
