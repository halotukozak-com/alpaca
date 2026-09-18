package halotukozak
package alpaca
package internal
package lexer

import halotukozak.regex.Subset

/**
 * Cross-platform shadow detection for token regex patterns.
 *
 * A pattern is shadowed if every string it matches is also matched (as a prefix)
 * by an earlier pattern, meaning the earlier pattern would always be selected first.
 * Implemented via Brzozowski-derivative DFA emptiness check on `L(later . Sigma*) subseteq L(earlier . Sigma*)`.
 */
private[lexer] object SubsetChecker:

  /**
   * Checks a priority-ordered sequence of pre-parsed regexes for shadowing.
   *
   * @return the shadowed/shadowing pair, if any pattern is shadowed by an earlier one.
   */
  def checkRegexes(items: List[(name: String, subset: Subset)]): Option[(first: String, second: String)] = items match
    case Nil => None
    case _ =>
      (for
        suffix <- items.tails
        if suffix.nonEmpty
        (earlierName, earlierSub) :: laters = suffix.runtimeChecked
        (laterName, laterSub) <- laters
        if laterSub.subset(earlierSub)
      yield (laterName, earlierName)).nextOption()
