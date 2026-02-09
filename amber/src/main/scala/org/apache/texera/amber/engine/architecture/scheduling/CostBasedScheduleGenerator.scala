/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.engine.architecture.scheduling

import org.apache.texera.amber.config.ApplicationConfig
import org.apache.texera.amber.core.storage.VFSURIFactory.createResultURI
import org.apache.texera.amber.core.virtualidentity.{ActorVirtualIdentity, PhysicalOpIdentity}
import org.apache.texera.amber.core.workflow._
import org.apache.texera.amber.engine.architecture.scheduling.SchedulingUtils.replaceVertex
import org.apache.texera.amber.engine.architecture.scheduling.config.{
  IntermediateInputPortConfig,
  OutputPortConfig,
  ResourceConfig
}
import org.apache.texera.amber.engine.common.AmberLogging
import org.apache.texera.amber.util.serde.GlobalPortIdentitySerde
import org.jgrapht.Graph
import org.jgrapht.alg.connectivity.BiconnectivityInspector
import org.jgrapht.graph.{DirectedAcyclicGraph, DirectedPseudograph}

import java.net.URI
import java.util.concurrent.TimeoutException
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Failure, Success, Try}

class CostBasedScheduleGenerator(
    workflowContext: WorkflowContext,
    initialPhysicalPlan: PhysicalPlan,
    val actorId: ActorVirtualIdentity
) extends ScheduleGenerator(
      workflowContext,
      initialPhysicalPlan
    )
    with AmberLogging {

  case class SearchResult(
      state: Set[PhysicalLink],
      regionDAG: DirectedAcyclicGraph[Region, RegionLink],
      cost: Double,
      searchTimeNanoSeconds: Long = 0,
      numStatesExplored: Int = 0
  )

  /**
    * Deterministic cache-aware propagation result under Assumption III (forced cache use).
    */
  private case class RequirednessAnalysis(
      requiredSeedPorts: Set[GlobalPortIdentity],
      requiredOutputPorts: Set[GlobalPortIdentity],
      executeOperators: Set[PhysicalOpIdentity],
      freshRequiredOutputPorts: Set[GlobalPortIdentity],
      cacheHitRequiredOutputPorts: Set[GlobalPortIdentity],
      freshRequiredLinks: Set[PhysicalLink],
      cacheFedLinks: Set[PhysicalLink]
  )

  private val costEstimator =
    new DefaultCostEstimator(
      workflowContext = workflowContext,
      resourceAllocator = resourceAllocator,
      actorId = actorId
    )

  private val cachedOutputsByPort: Map[GlobalPortIdentity, CachedOutput] =
    workflowContext.workflowSettings.cachedOutputs.map {
      case (serializedId, cachedOutput) =>
        GlobalPortIdentitySerde.deserializeFromString(serializedId) -> cachedOutput
    }

  /**
    * Search-time planning bindings applied during region construction.
    *
    * @param visibleFreshOutputPorts visible/sink output ports that must be freshly materialized.
    * @param reuseOnlyOutputsByPort output ports that are required but must be served from cache (no rematerialization).
    * @param cacheFedInputUrisByPort input ports pre-bound to cached upstream URIs.
    */
  private case class PlanningBindings(
      visibleFreshOutputPorts: Set[GlobalPortIdentity],
      reuseOnlyOutputsByPort: Map[GlobalPortIdentity, CachedOutput],
      cacheFedInputUrisByPort: Map[GlobalPortIdentity, List[URI]]
  )

  private object PlanningBindings {
    val empty: PlanningBindings = PlanningBindings(
      visibleFreshOutputPorts = Set.empty,
      reuseOnlyOutputsByPort = Map.empty,
      cacheFedInputUrisByPort = Map.empty
    )
  }

  private var activePlanningPlan: PhysicalPlan = initialPhysicalPlan
  private var activePlanningBindings: PlanningBindings = PlanningBindings.empty

  private case class CostEstimatorMemoKey(
      physicalOpIds: Set[PhysicalOpIdentity],
      physicalLinkIds: Set[PhysicalLink],
      portIds: Set[GlobalPortIdentity],
      resourceConfig: Option[ResourceConfig]
  )

  private val costEstimatorMemoization
      : mutable.Map[CostEstimatorMemoKey, (ResourceConfig, Double)] =
    new mutable.HashMap()

  def generate(): (Schedule, PhysicalPlan) = {
    val startTime = System.nanoTime()
    val analysis = analyzeRequiredness(initialPhysicalPlan)

    val executeRegionDAG = new DirectedAcyclicGraph[Region, RegionLink](classOf[RegionLink])
    if (analysis.executeOperators.nonEmpty) {
      val residualPlan = buildResidualPlan(
        initialPhysicalPlan,
        analysis.executeOperators,
        analysis.freshRequiredLinks
      )
      val residualBindings = buildResidualPlanningBindings(residualPlan, analysis)
      val searchResult = searchOnPlan(residualPlan, residualBindings)
      searchResult.regionDAG.vertexSet().forEach(region => executeRegionDAG.addVertex(region))
      searchResult.regionDAG
        .edgeSet()
        .forEach(edge =>
          executeRegionDAG.addEdge(
            searchResult.regionDAG.getEdgeSource(edge),
            searchResult.regionDAG.getEdgeTarget(edge),
            edge
          )
        )
    }

    val skipRegions = buildSkipRegions(initialPhysicalPlan, analysis)
    val finalRegionPlan = buildFinalRegionPlan(executeRegionDAG, skipRegions)
    val totalRPGTime = System.nanoTime() - startTime
    val schedule = generateScheduleFromRegionPlan(finalRegionPlan)
    logger.info(
      s"WID: ${workflowContext.workflowId.id}, EID: ${workflowContext.executionId.id}, total RPG time: " +
        s"${totalRPGTime / 1e6} ms."
    )
    (
      schedule,
      initialPhysicalPlan
    )
  }

  /**
    * Computes deterministic requiredness and execute/skip decisions under Assumption III (forced cache use).
    */
  private def analyzeRequiredness(plan: PhysicalPlan): RequirednessAnalysis = {
    val requiredSeeds =
      workflowContext.workflowSettings.outputPortsNeedingStorage
        .filter(pid => plan.operators.exists(_.id == pid.opId))

    val requiredOutputPorts = mutable.Set[GlobalPortIdentity]()
    requiredOutputPorts ++= requiredSeeds
    val executeOperators = mutable.Set[PhysicalOpIdentity]()

    var changed = true
    while (changed) {
      changed = false
      plan.topologicalIterator().foreach { opId =>
        val op = plan.getOperator(opId)
        val requiredOutputsOfOp =
          op.outputPorts.keys
            .map(portId => GlobalPortIdentity(op.id, portId))
            .filter(requiredOutputPorts.contains)
            .toSet
        if (requiredOutputsOfOp.nonEmpty) {
          val hasCacheMiss = requiredOutputsOfOp.exists(pid => !cachedOutputsByPort.contains(pid))
          if (hasCacheMiss) {
            if (!executeOperators.contains(opId)) {
              executeOperators += opId
              changed = true
            }
            plan.getUpstreamPhysicalLinks(opId).foreach { link =>
              val upstreamOutput = GlobalPortIdentity(link.fromOpId, link.fromPortId)
              if (!requiredOutputPorts.contains(upstreamOutput)) {
                requiredOutputPorts += upstreamOutput
                changed = true
              }
            }
          }
        }
      }
    }

    val freshRequiredOutputPorts =
      requiredOutputPorts.filter(pid => !cachedOutputsByPort.contains(pid)).toSet
    val cacheHitRequiredOutputPorts =
      requiredOutputPorts.filter(cachedOutputsByPort.contains).toSet
    val linksIntoExecute = plan.links.filter(link => executeOperators.contains(link.toOpId))
    val freshRequiredLinks = linksIntoExecute
      .filter(link =>
        freshRequiredOutputPorts.contains(GlobalPortIdentity(link.fromOpId, link.fromPortId))
      )
      .toSet
    val cacheFedLinks = linksIntoExecute
      .filter(link =>
        cacheHitRequiredOutputPorts.contains(GlobalPortIdentity(link.fromOpId, link.fromPortId))
      )
      .toSet

    RequirednessAnalysis(
      requiredSeedPorts = requiredSeeds,
      requiredOutputPorts = requiredOutputPorts.toSet,
      executeOperators = executeOperators.toSet,
      freshRequiredOutputPorts = freshRequiredOutputPorts,
      cacheHitRequiredOutputPorts = cacheHitRequiredOutputPorts,
      freshRequiredLinks = freshRequiredLinks,
      cacheFedLinks = cacheFedLinks
    )
  }

  /**
    * Builds a residual plan containing only execute operators and fresh-required dependencies.
    */
  private def buildResidualPlan(
      plan: PhysicalPlan,
      executeOperators: Set[PhysicalOpIdentity],
      freshRequiredLinks: Set[PhysicalLink]
  ): PhysicalPlan = {
    val linksToRemove = plan.links.diff(freshRequiredLinks)
    val linksPrunedPlan = linksToRemove.foldLeft(plan)((acc, link) => acc.removeLink(link))
    linksPrunedPlan.getSubPlan(executeOperators)
  }

  /**
    * Builds the search-time binding context for residual Pasta planning.
    * All bindings are prepared before search and consumed directly by createRegions.
    */
  private def buildResidualPlanningBindings(
      residualPlan: PhysicalPlan,
      analysis: RequirednessAnalysis
  ): PlanningBindings = {
    val residualOps = residualPlan.operators.map(_.id).toSet

    val visibleFreshOutputPorts = analysis.requiredSeedPorts
      .intersect(analysis.freshRequiredOutputPorts)
      .filter(pid => residualOps.contains(pid.opId))

    val reuseOnlyOutputsByPort = analysis.cacheHitRequiredOutputPorts
      .filter(pid => residualOps.contains(pid.opId))
      .flatMap(pid => cachedOutputsByPort.get(pid).map(cached => pid -> cached))
      .toMap

    val cacheFedInputUrisByPort = analysis.cacheFedLinks
      .filter(link => residualOps.contains(link.toOpId))
      .foldLeft(Map.empty[GlobalPortIdentity, List[URI]]) {
        case (acc, link) =>
          val inputPort = GlobalPortIdentity(link.toOpId, link.toPortId, input = true)
          val outputPort = GlobalPortIdentity(link.fromOpId, link.fromPortId)
          cachedOutputsByPort.get(outputPort) match {
            case Some(cached) =>
              val uris = acc.getOrElse(inputPort, List.empty) :+ cached.resultUri
              acc.updated(inputPort, uris)
            case None =>
              acc
          }
      }

    PlanningBindings(
      visibleFreshOutputPorts = visibleFreshOutputPorts,
      reuseOnlyOutputsByPort = reuseOnlyOutputsByPort,
      cacheFedInputUrisByPort = cacheFedInputUrisByPort
    )
  }

  /**
    * Runs the original Pasta search on a provided plan with precomputed planning bindings.
    */
  private def searchOnPlan(
      plan: PhysicalPlan,
      planningBindings: PlanningBindings
  ): SearchResult = {
    val oldPlan = activePlanningPlan
    val oldBindings = activePlanningBindings
    try {
      activePlanningPlan = plan
      activePlanningBindings = planningBindings
      costEstimatorMemoization.clear()
      runSearchWithTimeout()
    } finally {
      activePlanningPlan = oldPlan
      activePlanningBindings = oldBindings
    }
  }

  /**
    * Builds skip regions over operators excluded from residual Pasta planning.
    */
  private def buildSkipRegions(
      plan: PhysicalPlan,
      analysis: RequirednessAnalysis
  ): Set[Region] = {
    val skipOperators = plan.operators.map(_.id).diff(analysis.executeOperators)
    if (skipOperators.isEmpty) {
      return Set.empty
    }
    val skipInternalLinks = plan.links
      .filter(link => skipOperators.contains(link.fromOpId) && skipOperators.contains(link.toOpId))
    val linksToRemove = plan.links.diff(skipInternalLinks)
    val linksPrunedPlan = linksToRemove.foldLeft(plan)((acc, link) => acc.removeLink(link))
    val skipPlan = linksPrunedPlan.getSubPlan(skipOperators)
    val skipRegionSkeletons = createRegions(skipPlan, Set.empty)
    skipRegionSkeletons.map { region =>
      val reuseOnlyOutputConfigs = analysis.cacheHitRequiredOutputPorts
        .filter(pid => region.physicalOps.exists(_.id == pid.opId))
        .flatMap { outputPort =>
          cachedOutputsByPort.get(outputPort).map { cached =>
            outputPort -> OutputPortConfig(
              storageURI = cached.resultUri,
              cachedTupleCount = cached.tupleCount,
              materialize = false
            )
          }
        }
        .toMap
      region.copy(
        cached = true,
        resourceConfig = Some(ResourceConfig(portConfigs = reuseOnlyOutputConfigs))
      )
    }
  }

  /**
    * Produces the final full region plan by combining skip regions and execute regions
    * (from residual Pasta), then reindexing region IDs with skipped regions first.
    */
  private def buildFinalRegionPlan(
      executeRegionDAG: DirectedAcyclicGraph[Region, RegionLink],
      skipRegions: Set[Region]
  ): RegionPlan = {
    val executeRegions = executeRegionDAG.vertexSet().asScala.toSet
    val executeLinks = executeRegionDAG.edgeSet().asScala.toSet
    val allRegions = executeRegions ++ skipRegions
    val orderedRegions = allRegions.toSeq.sortBy(region =>
      (if (skipRegions.contains(region)) 0 else 1, region.id.id)
    )
    val remappedRegionByRegion = orderedRegions.zipWithIndex.map {
      case (region, idx) => region -> region.copy(id = RegionIdentity(idx.toLong))
    }.toMap
    val executeRegionById = executeRegions.map(region => region.id -> region).toMap

    val remappedRegions = remappedRegionByRegion.values.toSet
    val remappedExecuteLinks = executeLinks.map { link =>
      val fromRegion = executeRegionById(link.fromRegionId)
      val toRegion = executeRegionById(link.toRegionId)
      RegionLink(
        remappedRegionByRegion(fromRegion).id,
        remappedRegionByRegion(toRegion).id
      )
    }
    RegionPlan(remappedRegions, remappedExecuteLinks)
  }

  /**
    * Partitions a physical plan into Regions and assigns search-time port bindings in two passes.
    *
    * Pass 1 assigns output bindings for:
    * - outputs of materialized edges in the current search state,
    * - visible required outputs that must be freshly produced,
    * - reuse-only required outputs that must bind to cached URIs.
    *
    * Pass 2 builds input bindings by wiring:
    * - materialized-edge reader URIs from pass 1,
    * - cache-fed input URIs precomputed during requiredness analysis.
    */
  private def createRegions(
      physicalPlan: PhysicalPlan,
      matEdges: Set[PhysicalLink]
  ): Set[Region] = {
    val matEdgesRemovedDAG: PhysicalPlan = matEdges.foldLeft(physicalPlan)(_.removeLink(_))

    val connectedComponents: Set[Graph[PhysicalOpIdentity, PhysicalLink]] =
      new BiconnectivityInspector[PhysicalOpIdentity, PhysicalLink](
        matEdgesRemovedDAG.dag
      ).getConnectedComponents.asScala.toSet

    val regionsWithOutputBindings: Set[Region] = connectedComponents.zipWithIndex.map {
      case (connectedSubDAG, idx) =>
        val operators: Set[PhysicalOpIdentity] = connectedSubDAG.vertexSet().asScala.toSet

        val links: Set[PhysicalLink] = operators
          .flatMap { opId =>
            physicalPlan.getUpstreamPhysicalLinks(opId) ++
              physicalPlan.getDownstreamPhysicalLinks(opId)
          }
          .filter(link => operators.contains(link.fromOpId))
          .diff(matEdges) // keep only pipelined edges

        val physicalOps: Set[PhysicalOp] = operators.map(physicalPlan.getOperator)

        val ports: Set[GlobalPortIdentity] = physicalOps.flatMap { op =>
          op.inputPorts.keys
            .map(inputPortId => GlobalPortIdentity(op.id, inputPortId, input = true))
            .toSet ++ op.outputPorts.keys
            .map(outputPortId => GlobalPortIdentity(op.id, outputPortId))
            .toSet
        }

        val outputPortIdsFromMatEdges = matEdges
          .filter(edge => operators.contains(edge.fromOpId))
          .map(edge => GlobalPortIdentity(edge.fromOpId, edge.fromPortId))
        val visibleFreshOutputPortIds = activePlanningBindings.visibleFreshOutputPorts
          .filter(portId => operators.contains(portId.opId))
        val reuseOnlyOutputPortIds = activePlanningBindings.reuseOnlyOutputsByPort.keySet
          .filter(portId => operators.contains(portId.opId))
        val outputPortIdsNeedingBindings =
          outputPortIdsFromMatEdges ++ visibleFreshOutputPortIds ++ reuseOnlyOutputPortIds

        val outputPortConfigs = outputPortIdsNeedingBindings.map { outputPortId =>
          activePlanningBindings.reuseOnlyOutputsByPort.get(outputPortId) match {
            case Some(cachedOutput) =>
              outputPortId -> OutputPortConfig(
                storageURI = cachedOutput.resultUri,
                cachedTupleCount = cachedOutput.tupleCount,
                materialize = false
              )
            case None =>
              outputPortId -> OutputPortConfig(
                storageURI = createResultURI(
                  workflowId = workflowContext.workflowId,
                  executionId = workflowContext.executionId,
                  globalPortId = outputPortId
                ),
                cachedTupleCount = None,
                materialize = true
              )
          }
        }.toMap

        val resourceConfig =
          if (outputPortConfigs.nonEmpty) Some(ResourceConfig(portConfigs = outputPortConfigs))
          else None

        Region(
          id = RegionIdentity(idx),
          physicalOps = physicalOps,
          physicalLinks = links,
          ports = ports,
          resourceConfig = resourceConfig,
          cached = false
        )
    }

    val regionByOperator = regionsWithOutputBindings
      .flatMap(region => region.getOperators.map(op => op.id -> region))
      .toMap

    regionsWithOutputBindings.map { region =>
      val inputUrisFromMatEdges = matEdges
        .filter(edge => region.physicalOps.exists(_.id == edge.toOpId))
        .foldLeft(Map.empty[GlobalPortIdentity, List[URI]]) {
          case (acc, matEdge) =>
            val globalInputPortId =
              GlobalPortIdentity(matEdge.toOpId, matEdge.toPortId, input = true)
            val globalOutputPortId = GlobalPortIdentity(matEdge.fromOpId, matEdge.fromPortId)
            val inputReaderUri = regionByOperator(matEdge.fromOpId)
              .resourceConfig
              .get
              .portConfigs(globalOutputPortId)
              .storageURIs
              .head
            acc.updated(
              globalInputPortId,
              acc.getOrElse(globalInputPortId, List.empty[URI]) :+ inputReaderUri
            )
        }

      val cacheFedInputUris = activePlanningBindings.cacheFedInputUrisByPort
        .filter { case (inputPortId, _) => region.ports.contains(inputPortId) }
        .toMap

      val mergedInputUris = (inputUrisFromMatEdges.keySet ++ cacheFedInputUris.keySet)
        .map { inputPortId =>
          val uris =
            inputUrisFromMatEdges.getOrElse(inputPortId, List.empty) ++
              cacheFedInputUris.getOrElse(inputPortId, List.empty)
          inputPortId -> uris
        }
        .toMap

      val inputPortConfigs = mergedInputUris.map {
        case (inputPortId, uris) =>
          inputPortId -> IntermediateInputPortConfig(uris)
      }

      val existingPortConfigs = region.resourceConfig.map(_.portConfigs).getOrElse(Map.empty)
      val newResourceConfig =
        if (inputPortConfigs.nonEmpty || existingPortConfigs.nonEmpty) {
          Some(ResourceConfig(portConfigs = existingPortConfigs ++ inputPortConfigs))
        } else {
          None
        }

      region.copy(resourceConfig = newResourceConfig)
    }
  }

  /**
    * Checks a plan for schedulability, and returns a region DAG if the plan is schedulable.
    *
    * @param matEdges Set of edges to materialize (including the original blocking edges).
    * @return If the plan is schedulable, a region DAG will be returned. Otherwise a DirectedPseudograph (with directed
    *         cycles) will be returned to indicate that the plan is unschedulable.
    */
  private def tryConnectRegionDAG(
      matEdges: Set[PhysicalLink]
  ): Either[DirectedAcyclicGraph[Region, RegionLink], DirectedPseudograph[Region, RegionLink]] = {
    val regionDAG = new DirectedAcyclicGraph[Region, RegionLink](classOf[RegionLink])
    val regionGraph = new DirectedPseudograph[Region, RegionLink](classOf[RegionLink])
    val opToRegionMap = new mutable.HashMap[PhysicalOpIdentity, Region]
    createRegions(activePlanningPlan, matEdges).foreach(region => {
      region.getOperators.foreach(op => opToRegionMap(op.id) = region)
      regionGraph.addVertex(region)
      regionDAG.addVertex(region)
    })
    var isAcyclic = true
    matEdges.foreach(matEdge => {
      val fromRegion = opToRegionMap(matEdge.fromOpId)
      val toRegion = opToRegionMap(matEdge.toOpId)
      regionGraph.addEdge(fromRegion, toRegion, RegionLink(fromRegion.id, toRegion.id))
      try {
        regionDAG.addEdge(fromRegion, toRegion, RegionLink(fromRegion.id, toRegion.id))
      } catch {
        case _: IllegalArgumentException =>
          isAcyclic = false
      }
    })
    if (isAcyclic) Left(regionDAG) else Right(regionGraph)
  }

  /**
    * Runs Pasta search with timeout handling and returns the selected search result.
    */
  private def runSearchWithTimeout(): SearchResult = {
    val searchResultFuture: Future[SearchResult] = Future {
      workflowContext.workflowSettings.executionMode match {
        case ExecutionMode.MATERIALIZED =>
          getFullyMaterializedSearchState
        case ExecutionMode.PIPELINED =>
          if (ApplicationConfig.useTopDownSearch)
            topDownSearch(globalSearch = ApplicationConfig.useGlobalSearch)
          else
            bottomUpSearch(globalSearch = ApplicationConfig.useGlobalSearch)
      }
    }
    val searchResult = Try(
      Await.result(searchResultFuture, ApplicationConfig.searchTimeoutMilliseconds.milliseconds)
    ) match {
      case Failure(exception) =>
        exception match {
          case _: TimeoutException =>
            logger.warn(
              s"WID: ${workflowContext.workflowId.id}, EID: ${workflowContext.executionId.id}, search for region plan " +
                s"timed out, falling back to bottom-up greedy search.",
              exception
            )
            bottomUpSearch()
          case _ => throw new RuntimeException(exception)
        }

      case Success(result) =>
        result
    }
    logger.info(
      s"WID: ${workflowContext.workflowId.id}, EID: ${workflowContext.executionId.id}, search time: " +
        s"${searchResult.searchTimeNanoSeconds / 1e6} ms."
    )
    searchResult
  }

  /**
    * The core of the search algorithm. If the input physical plan is already schedulable, no search will be executed.
    * Otherwise, depending on the configuration, either a global search or a greedy search will be performed to find
    * an optimal plan. The search starts from a plan where all non-blocking edges are pipelined, and leads to a low-cost
    * schedulable plan by changing pipelined non-blocking edges to materialized. By default all pruning techniques
    * are enabled (chains, clean edges, and early stopping on schedulable states).
    *
    * @return A SearchResult containing the plan, the region DAG (without materializations added yet), the cost, the
    *         time to finish search, and the number of states explored.
    */
  def bottomUpSearch(
      globalSearch: Boolean = false,
      oChains: Boolean = false,
      oCleanEdges: Boolean = false,
      oEarlyStop: Boolean = true
  ): SearchResult = {
    val startTime = System.nanoTime()
    val originalNonBlockingEdges =
      if (oCleanEdges) {
        activePlanningPlan.getNonBridgeNonBlockingLinks
      } else {
        activePlanningPlan.links.diff(
          activePlanningPlan.getBlockingAndDependeeLinks
        )
      }
    // Queue to hold states to be explored, starting with the empty set
    val queue: mutable.Queue[Set[PhysicalLink]] = mutable.Queue(Set.empty[PhysicalLink])
    // Keep track of visited states to avoid revisiting
    val visited: mutable.Set[Set[PhysicalLink]] = mutable.Set.empty[Set[PhysicalLink]]
    // Used for the Early Stop optimization technique
    val schedulableStates: mutable.Set[Set[PhysicalLink]] = mutable.Set.empty[Set[PhysicalLink]]
    // Initialize the bestResult with an impossible high cost for comparison
    var bestResult: SearchResult = SearchResult(
      state = Set.empty,
      regionDAG = new DirectedAcyclicGraph[Region, RegionLink](classOf[RegionLink]),
      cost = Double.PositiveInfinity
    )

    while (queue.nonEmpty) {
      // A state is represented as a set of materialized non-blocking edges.
      val currentState = queue.dequeue()
      breakable {
        if (
          oEarlyStop && schedulableStates
            .exists(ancestorState => ancestorState.subsetOf(currentState))
        ) {
          // Early stop: stopping exploring states beyond a schedulable state since the cost will only increase.
          // A state X is a descendant of an ancestor state Y in the bottom-up search process if Y's set of materialized
          // edges is a subset of that of X's (since X is reachable from Y by adding more materialized edges.)
          break()
        }
        visited.add(currentState)
        tryConnectRegionDAG(
          activePlanningPlan.getBlockingAndDependeeLinks ++ currentState
        ) match {
          case Left(regionDAG) =>
            updateOptimumIfApplicable(regionDAG)
            addNeighborStatesToFrontier()
          case Right(_) =>
            addNeighborStatesToFrontier()
        }
      }

      /**
        * An internal method of bottom-up search that updates the current optimum if the examined state is schedulable
        * and has a lower cost.
        */
      def updateOptimumIfApplicable(regionDAG: DirectedAcyclicGraph[Region, RegionLink]): Unit = {
        if (oEarlyStop) schedulableStates.add(currentState)
        // Calculate the current state's cost and update the bestResult if it's lower
        val cost = allocateResourcesAndEvaluateCost(regionDAG)
        if (cost < bestResult.cost) {
          bestResult = SearchResult(currentState, regionDAG, cost)
        }
      }

      /**
        * An internal method of bottom-up search that performs state transitions (changing an pipelined edge to
        * materialized) to include the unvisited neighbor(s) of the current state in the frontier (i.e., the queue).
        * If using global search, all unvisited neighbors will be included. Otherwise in a greedy search, only the
        * neighbor with the lowest cost will be included.
        */
      def addNeighborStatesToFrontier(): Unit = {
        val allCurrentMaterializedEdges =
          currentState ++ activePlanningPlan.getBlockingAndDependeeLinks
        // Generate and enqueue all neighbour states that haven't been visited
        var candidateEdges = originalNonBlockingEdges
          .diff(currentState)
        if (oChains) {
          val edgesInChainWithMaterializedEdges = activePlanningPlan.maxChains
            .filter(chain => chain.intersect(allCurrentMaterializedEdges).nonEmpty)
            .flatten
          candidateEdges = candidateEdges.diff(
            edgesInChainWithMaterializedEdges
          ) // Edges in chain with blocking edges should not be materialized
        }

        val unvisitedNeighborStates = candidateEdges
          .map(edge => currentState + edge)
          .filter(neighborState =>
            !visited.contains(neighborState) && !queue.contains(neighborState)
          )

        val filteredNeighborStates = if (oEarlyStop) {
          // Any descendant state of a schedulable state is not worth exploring.
          unvisitedNeighborStates.filter(neighborState =>
            !schedulableStates.exists(ancestorState => ancestorState.subsetOf(neighborState))
          )
        } else {
          unvisitedNeighborStates
        }

        if (globalSearch) {
          // include all unvisited neighbors
          filteredNeighborStates.foreach(neighborState => queue.enqueue(neighborState))
        } else {
          // greedy search, only include an unvisited neighbor with the lowest cost
          if (filteredNeighborStates.nonEmpty) {
            val minCostNeighborState = filteredNeighborStates.minBy(neighborState =>
              tryConnectRegionDAG(
                activePlanningPlan.getBlockingAndDependeeLinks ++ neighborState
              ) match {
                case Left(regionDAG) =>
                  allocateResourcesAndEvaluateCost(regionDAG)
                case Right(_) =>
                  Double.MaxValue
              }
            )
            queue.enqueue(minCostNeighborState)
          }
        }
      }
    }

    val searchTime = System.nanoTime() - startTime
    bestResult.copy(
      searchTimeNanoSeconds = searchTime,
      numStatesExplored = visited.size
    )
  }

  /** Constructs a baseline fully materialized region plan (one operator per region) and evaluates its cost. */
  def getFullyMaterializedSearchState: SearchResult = {
    val startTime = System.nanoTime()

    val (regionDAG, cost) =
      tryConnectRegionDAG(activePlanningPlan.links) match {
        case Left(dag) => (dag, allocateResourcesAndEvaluateCost(dag))
        case Right(_) =>
          (
            new DirectedAcyclicGraph[Region, RegionLink](classOf[RegionLink]),
            Double.PositiveInfinity
          )
      }

    SearchResult(
      state = Set.empty,
      regionDAG = regionDAG,
      cost = cost,
      searchTimeNanoSeconds = System.nanoTime() - startTime,
      numStatesExplored = 1
    )
  }

  /**
    * Another direction to perform the search. Depending on the configuration, either a global search or a greedy search
    * will be performed to find an optimal plan. The search starts from a plan where all edges are materialized, and
    * leads to a low-cost schedulable plan by changing materialized non-blocking edges to pipelined.
    * By default, all pruning techniques are enabled (chains, clean edges).
    *
    * @return A SearchResult containing the plan, the region DAG (without materializations added yet), the cost, the
    *         time to finish search, and the number of states explored.
    */
  def topDownSearch(
      globalSearch: Boolean = false,
      oChains: Boolean = false,
      oCleanEdges: Boolean = false
  ): SearchResult = {
    val startTime = System.nanoTime()
    // Starting from a state where all non-blocking edges are materialized
    val originalSeedState = activePlanningPlan.links.diff(
      activePlanningPlan.getBlockingAndDependeeLinks
    )

    // Chain optimization: an edge in the same chain as a blocking edge should not be materialized
    val seedStateOptimizedByChainsIfApplicable = if (oChains) {
      val edgesInChainWithBlockingEdge = activePlanningPlan.maxChains
        .filter(chain => chain.intersect(activePlanningPlan.getBlockingAndDependeeLinks).nonEmpty)
        .flatten
      originalSeedState.diff(edgesInChainWithBlockingEdge)
    } else {
      originalSeedState
    }

    // Clean edge optimization: a clean edge should not be materialized
    val finalSeedState = if (oCleanEdges) {
      seedStateOptimizedByChainsIfApplicable.intersect(activePlanningPlan.getNonBridgeNonBlockingLinks)
    } else {
      seedStateOptimizedByChainsIfApplicable
    }

    // Queue to hold states to be explored, starting with the seed state
    val queue: mutable.Queue[Set[PhysicalLink]] = mutable.Queue(finalSeedState)
    // Keep track of visited states to avoid revisiting
    val visited: mutable.Set[Set[PhysicalLink]] = mutable.Set.empty[Set[PhysicalLink]]
    // Initialize the bestResult with an impossible high cost for comparison
    var bestResult: SearchResult = SearchResult(
      state = Set.empty,
      regionDAG = new DirectedAcyclicGraph[Region, RegionLink](classOf[RegionLink]),
      cost = Double.PositiveInfinity
    )

    while (queue.nonEmpty) {
      val currentState = queue.dequeue()
      visited.add(currentState)
      tryConnectRegionDAG(
        activePlanningPlan.getBlockingAndDependeeLinks ++ currentState
      ) match {
        case Left(regionDAG) =>
          updateOptimumIfApplicable(regionDAG)
          addNeighborStatesToFrontier()
        // No need to explore further
        case Right(_) =>
          addNeighborStatesToFrontier()
      }

      /**
        * An internal method of top-down search that updates the current optimum if the examined state is schedulable
        * and has a lower cost.
        */
      def updateOptimumIfApplicable(regionDAG: DirectedAcyclicGraph[Region, RegionLink]): Unit = {
        // Calculate the current state's cost and update the bestResult if it's lower
        val cost = allocateResourcesAndEvaluateCost(regionDAG)
        if (cost < bestResult.cost) {
          bestResult = SearchResult(currentState, regionDAG, cost)
        }
      }

      /**
        * An internal method of top-down search that performs state transitions (changing an materialized edge to
        * pipelined) to include the unvisited neighbor(s) of the current state in the frontier (i.e., the queue).
        * If using global search, all unvisited neighbors will be included. Otherwise in a greedy search, only the
        * neighbor with the lowest cost will be included.
        */
      def addNeighborStatesToFrontier(): Unit = {
        val unvisitedNeighborStates = currentState
          .map(edge => currentState - edge)
          .filter(neighborState =>
            !visited.contains(neighborState) && !queue.contains(neighborState)
          )

        if (globalSearch) {
          // include all unvisited neighbors
          unvisitedNeighborStates.foreach(neighborState => queue.enqueue(neighborState))
        } else {
          // greedy search, only include an unvisited neighbor with the lowest cost
          if (unvisitedNeighborStates.nonEmpty) {
            val minCostNeighborState = unvisitedNeighborStates.minBy(neighborState =>
              tryConnectRegionDAG(
                activePlanningPlan.getBlockingAndDependeeLinks ++ neighborState
              ) match {
                case Left(regionDAG) =>
                  allocateResourcesAndEvaluateCost(regionDAG)
                case Right(_) =>
                  Double.MaxValue
              }
            )
            queue.enqueue(minCostNeighborState)
          }
        }
      }
    }

    val searchTime = System.nanoTime() - startTime
    bestResult.copy(
      searchTimeNanoSeconds = searchTime,
      numStatesExplored = visited.size
    )
  }

  /**
    * Takes a region DAG, generates one or more (to be done in the future) schedules based on the region DAG, allocates
    * resources to each region in the region DAG, and calculates the cost of the schedule(s) using Cost Estimator. Uses
    * the cost of the best schedule (currently only considers one schedule) as the cost of the region DAG.
    *
    * @return A cost determined by the cost estimator.
    */
  private def allocateResourcesAndEvaluateCost(
      regionDAG: DirectedAcyclicGraph[Region, RegionLink]
  ): Double = {
    val regionPlan =
      RegionPlan(regionDAG.vertexSet().asScala.toSet, regionDAG.edgeSet().asScala.toSet)
    val schedule = generateScheduleFromRegionPlan(regionPlan)
    // In the future we may allow multiple regions in a level and split the resources.
    schedule
      .map(level =>
        level
          .map(region => {
            val costEstimatorMemoKey = CostEstimatorMemoKey(
              physicalOpIds = region.physicalOps.map(_.id),
              physicalLinkIds = region.physicalLinks,
              portIds = region.ports,
              resourceConfig = region.resourceConfig
            )
            val (resourceConfig, regionCost) = costEstimatorMemoization
              .getOrElseUpdate(
                costEstimatorMemoKey,
                costEstimator.allocateResourcesAndEstimateCost(region, 1)
              )
            // Update the region in the regionDAG to be the new region with resources allocated.
            val regionWithResourceConfig = region.copy(resourceConfig = Some(resourceConfig))
            replaceVertex(regionDAG, region, regionWithResourceConfig)
            regionCost
          })
          .sum
      )
      .sum
  }

}
