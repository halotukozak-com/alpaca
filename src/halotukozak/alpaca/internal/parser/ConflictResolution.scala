package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.Showable

import scala.annotation.tailrec
import scala.collection.mutable

/** A key in the conflict resolution table: the production a reduction uses, or the token a shift reads. */
private[parser] enum ConflictKey:
  case Reduction(production: Production)
  case Shift(token: Printable)

private[parser] object ConflictKey:

  /** In the same form conflict messages use: productions as `lhs -> rhs (name)`, tokens quoted. */
  given Showable[ConflictKey] =
    case Reduction(production) => production.show
    case Shift(token) => show"\"$token\""

/**
 * Opaque type representing a table of conflict resolution rules.
 *
 * This maps each production/token to the productions/tokens that it has precedence over, each with the source of the
 * rule that declared it.
 */
opaque private[parser] type ConflictResolutionTable = Map[ConflictKey, Map[ConflictKey, Source]]

private[parser] object ConflictResolutionTable:

  /**
   * Creates a ConflictResolutionTable from a map of resolutions.
   *
   * @param resolutions the resolution map
   * @return a new ConflictResolutionTable
   */
  def apply(resolutions: Map[ConflictKey, Map[ConflictKey, Source]]): ConflictResolutionTable = resolutions

  extension (table: ConflictResolutionTable) {

    /**
     * Resolves a conflict between two parse actions.
     *
     * Uses the precedence rules in the table to determine which action
     * should be preferred. Returns None if no resolution rule applies.
     *
     * @param first  the first parse action
     * @param second the second parse action
     * @param symbol the symbol causing the conflict
     * @return Some(action) if one action has precedence, None otherwise
     */
    def get(first: ParseAction, second: ParseAction)(symbol: Symbol): Option[ParseAction] = {
      extension (action: ParseAction)
        def toConflictKey: ConflictKey = action match
          case ParseAction.Reduction(prod) => ConflictKey.Reduction(prod)
          case _: ParseAction.Shift => ConflictKey.Shift(symbol.name)

      def winsOver(first: ParseAction, second: ParseAction): Option[ParseAction] = {
        val to = second.toConflictKey
        val queue = mutable.ArrayDeque[ConflictKey](first.toConflictKey)

        @tailrec
        def loop(visited: Set[ConflictKey]): Option[ParseAction] = queue.removeHeadOption() match
          case None => None
          case Some(`to`) => Some(first)
          case Some(head) if visited contains head => loop(visited)
          case Some(head) =>
            table.get(head).foreach(afters => queue.appendAll(afters.keys))
            loop(visited + head)

        loop(Set.empty)
      }

      winsOver(first, second).orElse(winsOver(second, first))
    }

    def verifyNoConflicts()(using Quotes): Unit = {
      enum VisitState:
        case Unvisited, Visited, Processed

      enum Action:
        case Enter(node: ConflictKey, path: List[ConflictKey] = Nil)
        case Leave(node: ConflictKey)

      val visited = mutable.Map.empty[ConflictKey, VisitState].withDefaultValue(VisitState.Unvisited)

      @tailrec
      def loop(stack: List[Action]): Unit = stack match {
        case Nil => // Done

        case Action.Leave(node) :: rest =>
          visited(node) = VisitState.Processed
          loop(rest)

        case Action.Enter(node, path) :: rest =>
          visited(node) match
            case VisitState.Processed => loop(rest)
            case VisitState.Visited =>
              // $COVERAGE-OFF$
              val (cycle, key) =
                import ConflictKey.given
                (path.reverse.dropWhile(_ != node).mkShow(" before "), node.show)
              errorAndAbort(
                show"""
                      |Inconsistent conflict resolution detected:
                      |$cycle before $key
                      |There are elements being both before and after $key at the same time.
                      |Consider revising the before/after rules to eliminate cycles
                      |""".trimMargin,
                table(path.head)(node),
              )
            // $COVERAGE-ON$
            case VisitState.Unvisited =>
              visited(node) = VisitState.Visited
              val neighbors = table.getOrElse(node, Map.empty).keys.map(Action.Enter(_, node :: path)).toList
              loop(neighbors ::: List(Action.Leave(node)) ::: rest)
      }

      for node <- table.keys do loop(Action.Enter(node) :: Nil)
    }

    def toMermaid: String = {
      val sb = new StringBuilder
      sb.append("graph TD\n")

      val idMap = mutable.HashMap.empty[ConflictKey, String]
      var prodIdx = 0
      var tokIdx = 0

      def nodeId(key: ConflictKey): String =
        idMap.getOrElseUpdate(
          key,
          key match
            case _: ConflictKey.Reduction =>
              prodIdx += 1
              s"P_$prodIdx"
            case _: ConflictKey.Shift =>
              tokIdx += 1
              s"T_$tokIdx",
        )

      def escapeLabel(label: String): String =
        label
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
          .replace("#", "#35;")

      def nodeLabel(key: ConflictKey): String = key match
        case ConflictKey.Reduction(production) => show"$production"
        case ConflictKey.Shift(token) => show"Token($token)"

      val nodes = (table.keySet ++ table.values.flatMap(_.keys)).toList.sortBy(nodeLabel)
      for node <- nodes do sb.append(s"  ${nodeId(node)}[\"${escapeLabel(nodeLabel(node))}\"]\n")

      for
        (from, afters) <- table.toList.sortBy { case (fromKey, _) => nodeLabel(fromKey) }
        to <- afters.keys.toList.sortBy(nodeLabel)
      do sb.append(s"  ${nodeId(from)} --> ${nodeId(to)}\n")

      sb.toString
    }
  }

  /**
   * Showable instance for displaying conflict resolution tables.
   */
  given Showable[ConflictResolutionTable] = { table =>
    given Showable[ConflictKey] =
      case ConflictKey.Reduction(production) => show"$production"
      case ConflictKey.Shift(token) => show"Token[$token]"

    table
      .map((k, v) => show"$k before ${v.keys.mkShow(", ")}")
      .mkShow("\n")
  }
