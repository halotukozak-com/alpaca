package halotukozak
package alpaca
package internal
package lexer

import halotukozak.regex.{Regex, Subset}

/**
 * Cross-platform shadow detection for token regex patterns.
 *
 * The lexer takes the longest match and breaks ties by declaration order, so a pattern can
 * never produce a token exactly when every string it matches is also matched by some earlier
 * pattern: on any such input the earlier one matches at least as much text and wins the tie.
 * That is `L(later) ⊆ L(earlier₁) ∪ … ∪ L(earlierₙ)`, checked via Brzozowski-derivative DFA
 * emptiness.
 *
 * A pattern that merely matches a *prefix* of a later one (`"a"` before `"ab"`) does not shadow
 * it: longest match lets the later one win on the longer input.
 */
private[lexer] object SubsetChecker:

  /**
   * Checks a priority-ordered sequence of pre-parsed regexes for shadowing.
   *
   * @return the first shadowed pattern together with the earlier patterns that cover it, if any.
   *         `second` holds a single name when one earlier pattern covers it on its own, otherwise
   *         every earlier pattern that overlaps it.
   */
  def checkRegexes(items: List[(name: String, subset: Subset)]): Option[(first: String, second: List[String])] =
    items.indices.iterator
      .flatMap: i =>
        val (laterName, laterSub) = items(i)
        val earlier = items.take(i)
        earlier.find((_, earlierSub) => laterSub.subset(earlierSub)) match
          case Some((earlierName, _)) => Some((first = laterName, second = List(earlierName)))
          case None =>
            // Only patterns that overlap `later` can contribute to covering it; checking the
            // union of just those keeps the automaton small for lexers with many tokens.
            val overlapping = earlier.filterNot((_, earlierSub) => Subset.of(laterSub.underlying & earlierSub.underlying).isEmpty)
            Option.when(
              overlapping.sizeIs > 1 && laterSub.subset(Subset.of(Regex.alt(overlapping.map(_.subset.underlying)))),
            )((first = laterName, second = overlapping.map(_.name)))
      .nextOption()
