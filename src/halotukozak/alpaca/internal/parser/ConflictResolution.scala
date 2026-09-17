package halotukozak
package alpaca
package internal
package parser

import halotukozak.alpaca.internal.{DebugSettings, Showable}

import scala.annotation.tailrec
import scala.collection.mutable
/**
 * Type representing a key in the conflict resolution table.
 *
 * A conflict key can be either a Production or a String (token name).
 */
opaque private[parser] type ConflictKey = Production | String

private[parser] object ConflictKey:
  inline def apply(key: Production | String): ConflictKey = key

  given Showable[ConflictKey] =
    case Production.NonEmpty(lhs, rhs, null, _) => show"Reduction(${rhs.mkShow(" ")} -> $lhs)"
    case Production.Empty(lhs, null, _) => show"Reduction(${Symbol.Empty} -> $lhs)"
    case p: Production => show"Reduction(${p.name.nn})"
    case s: String => show"Shift($s)"

/**
 * Opaque type representing a table of conflict resolution rules.
 *
 * This maps each production/token to a set of productions/tokens that it has
 * precedence over.
 */
opaque private[parser] type ConflictResolutionTable = Map[ConflictKey, Set[ConflictKey]]

private[parser] object ConflictResolutionTable:

  /**
   * Creates a ConflictResolutionTable from a map of resolutions.
   *
   * @param resolutions the resolution map
   * @return a new ConflictResolutionTable
   */
  def apply(resolutions: Map[ConflictKey, Set[ConflictKey]]): ConflictResolutionTable = resolutions

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
          case ParseAction.Reduction(prod) => prod
          case _: ParseAction.Shift => symbol.name

      def winsOver(first: ParseAction, second: ParseAction): Option[ParseAction] = {
        val to = second.toConflictKey
        val queue = mutable.ArrayDeque[ConflictKey](first.toConflictKey)

        @tailrec
        def loop(visited: Set[ConflictKey]): Option[ParseAction] = queue.removeHeadOption() match
          case None => None
          case Some(`to`) => Some(first)
          case Some(head) if visited contains head => loop(visited)
          case Some(head) =>
            table.get(head).foreach(queue.appendAll)
            loop(visited + head)

        loop(Set.empty)
      }

      winsOver(first, second).orElse(winsOver(second, first))
    }

    def verifyNoConflicts()(using DebugSettings, Quotes): Unit = {
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
              quotes.reflect.report.errorAndAbort(
                show"""
                      |Inconsistent conflict resolution detected:
                      |${path.reverseIterator.dropWhile(_ != node).mkShow(" before ")} before $node
                      |There are elements being both before and after $node at the same time.
                      |Consider revising the before/after rules to eliminate cycles
                      |""".stripMargin,
              )
            case VisitState.Unvisited =>
              visited(node) = VisitState.Visited
              val neighbors = table.getOrElse(node, Set.empty).map(Action.Enter(_, node :: path)).toList
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
            case _: Production =>
              prodIdx += 1
              s"P_$prodIdx"
            case _: String =>
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
        case p: Production => show"$p"
        case s: String => show"Token($s)"

      val nodes = (table.keySet ++ table.values.flatten).toList.sortBy(nodeLabel)
      for node <- nodes do sb.append(s"  ${nodeId(node)}[\"${escapeLabel(nodeLabel(node))}\"]\n")

      for
        (from, toSet) <- table.toList.sortBy { case (fromKey, _) => nodeLabel(fromKey) }
        to <- toSet.toList.sortBy(nodeLabel)
      do sb.append(s"  ${nodeId(from)} --> ${nodeId(to)}\n")

      sb.toString
    }
  }

  /**
   * Showable instance for displaying conflict resolution tables.
   */
  given Showable[ConflictResolutionTable] = { table =>
    given Showable[ConflictKey] =
      case p: Production => show"$p"
      case s: String => show"Token[$s]"

    table
      .map((k, v) => show"$k before ${v.mkShow(", ")}")
      .mkShow("\n")
  }
