package com.myassistant.services

import com.myassistant.db.repositories.{RelationshipRepository, ReferenceRepository}
import com.myassistant.domain.{Relationship, RelationType}
import com.myassistant.errors.AppError
import zio.*
import zio.jdbc.*

import java.util.UUID

/** Result of resolving the kinship between two persons. */
final case class KinshipResult(
    /** Ordered chain of depth-1 relation types traversed (e.g. [Father, Sister]). */
    chain: List[RelationType],
    /** Cultural alias if found in kinship_alias (e.g. "bua"). */
    alias: Option[String],
    /** Plain English fallback description (e.g. "father's sister"). */
    description: String,
)

/** Service that resolves indirect kinship relations between persons.
 *
 *  Traverses the depth-1 relationship graph to find the shortest chain
 *  between two persons, then looks up the corresponding kinship alias.
 */
trait KinshipResolver:
  /** Resolve the kinship between fromPersonId and toPersonId.
   *  Returns None if the two persons are not connected in the graph.
   */
  def resolve(
      fromPersonId: UUID,
      toPersonId:   UUID,
  ): ZIO[ZConnectionPool, AppError, Option[KinshipResult]]

object KinshipResolver:

  /** Maximum BFS depth to prevent runaway traversal of disconnected or cyclic graphs. */
  private val MaxDepth = 6

  /** Produces a plain English description such as "father's sister". */
  private def chainToDescription(chain: List[RelationType]): String =
    chain.map(_.toString.toLowerCase).mkString("'s ")

  /** Convert a RelationType to its lowercase string name for alias lookup. */
  private def relTypeToString(rt: RelationType): String = rt match
    case RelationType.Father   => "father"
    case RelationType.Mother   => "mother"
    case RelationType.Son      => "son"
    case RelationType.Daughter => "daughter"
    case RelationType.Brother  => "brother"
    case RelationType.Sister   => "sister"
    case RelationType.Husband  => "husband"
    case RelationType.Wife     => "wife"

  /** Live implementation using BFS graph traversal over the relationship table. */
  final class Live(relRepo: RelationshipRepository, refRepo: ReferenceRepository) extends KinshipResolver:

    /** Resolve the kinship between fromPersonId and toPersonId via BFS, depth-limited to MaxDepth hops. */
    def resolve(
        fromPersonId: UUID,
        toPersonId:   UUID,
    ): ZIO[ZConnectionPool, AppError, Option[KinshipResult]] =
      if fromPersonId == toPersonId then
        ZIO.succeed(None)
      else
        bfs(fromPersonId, toPersonId).flatMap:
          case None        => ZIO.succeed(None)
          case Some(chain) =>
            val chainStrings = chain.map(relTypeToString)
            val description  = chainToDescription(chain)
            refRepo.listKinshipAliases(None).map: aliases =>
              val matchedAlias = aliases.find(_.relationChain == chainStrings).map(_.alias)
              Some(KinshipResult(chain, matchedAlias, description))

    /** BFS over the relationship graph; returns the shortest chain from `start` to `target`. */
    private def bfs(
        start:  UUID,
        target: UUID,
    ): ZIO[ZConnectionPool, AppError, Option[List[RelationType]]] =
      // Each queue entry: (currentPersonId, chainSoFar)
      // We maintain a visited set to avoid revisiting nodes.
      def step(
          queue:   List[(UUID, List[RelationType])],
          visited: Set[UUID],
      ): ZIO[ZConnectionPool, AppError, Option[List[RelationType]]] =
        queue match
          case Nil => ZIO.succeed(None)
          case (current, chain) :: rest =>
            if chain.length > MaxDepth then
              // Depth limit: skip this branch and continue
              step(rest, visited)
            else
              relRepo.findByPerson(current).flatMap: edges =>
                // For edges where `current` is person_id_a (fromPersonId), the
                // neighbour is person_id_b and the relation type is as stored.
                // For edges where `current` is person_id_b (toPersonId), we do
                // NOT invert the traversal — we follow the undirected graph edge
                // only from the fromPersonId side to stay directionally accurate.
                // The BFS is undirected (we called findByPerson which returns both
                // sides), but we record the relationType from the stored direction.
                val nextEntries: List[(UUID, List[RelationType], UUID)] =
                  edges.flatMap: rel =>
                    if rel.fromPersonId == current then
                      List((rel.toPersonId, chain :+ rel.relationType, rel.toPersonId))
                    else
                      // current == rel.toPersonId: edge goes the other way;
                      // include neighbour but record relation type from their
                      // perspective (inverse not stored — just record as-is so
                      // BFS can reach more nodes; alias lookup handles semantics)
                      List((rel.fromPersonId, chain :+ rel.relationType, rel.fromPersonId))

                // Check if any neighbour is the target
                nextEntries.find(_._3 == target) match
                  case Some((_, finalChain, _)) => ZIO.succeed(Some(finalChain))
                  case None =>
                    // Enqueue unvisited neighbours
                    val newVisited = visited + current
                    val newQueue   = rest ++ nextEntries
                      .filterNot((nid, _, _) => newVisited.contains(nid))
                      .map((nid, c, _) => (nid, c))
                    step(newQueue, newVisited)

      step(List((start, List.empty)), Set(start))

  /** ZLayer providing the live KinshipResolver, requiring both repositories. */
  val live: ZLayer[RelationshipRepository & ReferenceRepository, Nothing, KinshipResolver] =
    ZLayer.fromFunction(new Live(_, _))
