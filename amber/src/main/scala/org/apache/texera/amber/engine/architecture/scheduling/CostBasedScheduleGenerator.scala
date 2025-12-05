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
import org.apache.amber.engine.common.AmberLogging
import org.apache.amber.util.serde.GlobalPortIdentitySerde
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

  private val costEstimator =
    new DefaultCostEstimator(
      workflowContext = workflowContext,
      resourceAllocator = resourceAllocator,
      actorId = actorId
    )

  private val cachedOutputsByPort
      : Map[GlobalPortIdentity, CachedOutput] = workflowContext.workflowSettings.cachedOutputs.map {
    case (serializedId, cachedOutput) =>
      GlobalPortIdentitySerde.deserializeFromString(serializedId) -> cachedOutput
  }

  private case class CostEstimatorMemoKey(
      physicalOpIds: Set[PhysicalOpIdentity],
      physicalLinkIds: Set[PhysicalLink],
      portIds: Set[GlobalPortIdentity],
      resourceConfig: Option[ResourceConfig]
  )

  /**
    * Classify regions as cached vs must-execute and rebuild port configs accordingly.
    * A region is must-execute if it has a visible port without cache, or if it feeds a region
    * that needs a materialization without cache (propagated upstream).
    */
  private def annotateRegionsWithCacheInfo(
      regionDAG: DirectedAcyclicGraph[Region, RegionLink],
      matEdges: Set[PhysicalLink],
      opToRegionMap: Map[PhysicalOpIdentity, Region]
  ): Unit = {
    val regions = regionDAG.vertexSet().asScala.toSet

    val needsByRegion = computePortsNeedingStorage(regions, matEdges, opToRegionMap)
    val mustExecute = computeMustExecuteRegions(needsByRegion, matEdges, opToRegionMap)
    val cachedRegions = regions.diff(mustExecute)
    val outputConfigByPort = buildOutputPortConfigs(needsByRegion, cachedRegions)
    val inputConfigByPort = buildInputPortConfigs(matEdges, outputConfigByPort)

    // Rebuild each region with refreshed port configs and cached flag.
    regions.foreach { region =>
      val outputCfgs = needsByRegion(region).map(pid => pid -> outputConfigByPort(pid)).toMap
      val inputCfgs = inputConfigByPort.filter { case (pid, _) => region.ports.contains(pid) }
      val portConfigs = outputCfgs ++ inputCfgs
      val newResourceConfig =
        if (portConfigs.nonEmpty) Some(ResourceConfig(portConfigs = portConfigs))
        else region.resourceConfig

      val newRegion = region.copy(
        resourceConfig = newResourceConfig,
        cached = cachedRegions.contains(region)
      )
      replaceVertex(regionDAG, region, newRegion)
    }
  }

  private def computePortsNeedingStorage(
      regions: Set[Region],
      matEdges: Set[PhysicalLink],
      opToRegionMap: Map[PhysicalOpIdentity, Region]
  ): Map[Region, Set[GlobalPortIdentity]] = {
    // Ports that require materialization for this run: scheduler-imposed (matEdges) + UI-visible ports.
    regions.map { region =>
      val fromMatEdges = matEdges
        .filter(e => opToRegionMap(e.fromOpId) == region)
        .map(e => GlobalPortIdentity(e.fromOpId, e.fromPortId))
      val visiblePorts = workflowContext.workflowSettings.outputPortsNeedingStorage
        .filter(pid => region.physicalOps.exists(_.id == pid.opId))
      region -> (fromMatEdges ++ visiblePorts)
    }.toMap
  }

  private def computeMustExecuteRegions(
      needsByRegion: Map[Region, Set[GlobalPortIdentity]],
      matEdges: Set[PhysicalLink],
      opToRegionMap: Map[PhysicalOpIdentity, Region]
  ): Set[Region] = {
    // Seed with regions that have any needed port lacking cache; then propagate upstream along materialized edges.
    val mustExecute = mutable.Set[Region]()
    needsByRegion.foreach {
      case (region, needs) =>
        if (needs.exists(pid => !cachedOutputsByPort.contains(pid))) {
          mustExecute += region
        }
    }
    var changed = true
    while (changed) {
      changed = false
      matEdges.foreach { e =>
        val fromRegion = opToRegionMap(e.fromOpId)
        val toRegion = opToRegionMap(e.toOpId)
        val outPort = GlobalPortIdentity(e.fromOpId, e.fromPortId)
        if (
          mustExecute.contains(toRegion) &&
          !cachedOutputsByPort.contains(outPort) &&
          !mustExecute.contains(fromRegion)
        ) {
          mustExecute += fromRegion
          changed = true
        }
      }
    }
    mustExecute.toSet
  }

  private def buildOutputPortConfigs(
      needsByRegion: Map[Region, Set[GlobalPortIdentity]],
      cachedRegions: Set[Region]
  ): Map[GlobalPortIdentity, OutputPortConfig] = {
    // For cached regions, reuse cached URI + tuple count; otherwise allocate new URI with no cached count.
    needsByRegion.flatMap {
      case (region, ports) =>
        ports.map { gpid =>
          val (uri, cachedCount) =
            if (cachedRegions.contains(region)) {
              val cached = cachedOutputsByPort(gpid)
              (cached.resultUri, cached.tupleCount)
            } else {
              (
                createResultURI(
                  workflowId = workflowContext.workflowId,
                  executionId = workflowContext.executionId,
                  globalPortId = gpid
                ),
                None
              )
            }
          gpid -> OutputPortConfig(uri, cachedCount)
        }
    }
  }

  private def buildInputPortConfigs(
      matEdges: Set[PhysicalLink],
      outputConfigByPort: Map[GlobalPortIdentity, OutputPortConfig]
  ): Map[GlobalPortIdentity, IntermediateInputPortConfig] = {
    matEdges
      .groupBy(e => GlobalPortIdentity(e.toOpId, e.toPortId, input = true))
      .map {
        case (inputPid, links) =>
          val uris = links
            .map(link =>
              outputConfigByPort(GlobalPortIdentity(link.fromOpId, link.fromPortId)).storageURI
            )
            .toList
          inputPid -> IntermediateInputPortConfig(uris)
      }
  }

  private val costEstimatorMemoization
      : mutable.Map[CostEstimatorMemoKey, (ResourceConfig, Double)] =
    new mutable.HashMap()

  def generate(): (Schedule, PhysicalPlan) = {
    val startTime = System.nanoTime()
    val regionDAG = createRegionDAG()
    val totalRPGTime = System.nanoTime() - startTime
    val regionPlan = RegionPlan(
      regions = regionDAG.iterator().asScala.toSet,
      regionLinks = regionDAG.edgeSet().asScala.toSet
    )
    val schedule = generateScheduleFromRegionPlan(regionPlan)
    logger.info(
      s"WID: ${workflowContext.workflowId.id}, EID: ${workflowContext.executionId.id}, total RPG time: " +
        s"${totalRPGTime / 1e6} ms."
    )
    (
      schedule,
      physicalPlan
    )
  }

  /**
    * Partitions a physical plan into Regions (skeletons). Cache vs must-execute
    * classification and URI assignment are applied later in annotateRegionsWithCacheInfo
    * using the RegionDAG and materialization edges.
    *
    * @param physicalPlan the original physical plan (without materializations)
    * @param matEdges     edges to be materialized (including blocking edges)
    * @return a set of `Region` skeletons (resourceConfig/cached filled later)
    */
  private def createRegions(
      physicalPlan: PhysicalPlan,
      matEdges: Set[PhysicalLink]
  ): Set[Region] = {

    // remove materialized edges and create connected components

    val matEdgesRemovedDAG: PhysicalPlan = matEdges.foldLeft(physicalPlan)(_.removeLink(_))

    val connectedComponents: Set[Graph[PhysicalOpIdentity, PhysicalLink]] =
      new BiconnectivityInspector[PhysicalOpIdentity, PhysicalLink](
        matEdgesRemovedDAG.dag
      ).getConnectedComponents.asScala.toSet

    //  build region skeletons with no materialization information

    val regionSkeletons: Set[Region] = connectedComponents.zipWithIndex.map {
      case (connectedSubDAG, idx) =>
        // Operators and intra‑region pipelined links

        val operators: Set[PhysicalOpIdentity] = connectedSubDAG.vertexSet().asScala.toSet

        val links: Set[PhysicalLink] = operators
          .flatMap { opId =>
            physicalPlan.getUpstreamPhysicalLinks(opId) ++
              physicalPlan.getDownstreamPhysicalLinks(opId)
          }
          .filter(link => operators.contains(link.fromOpId))
          .diff(matEdges) // keep only pipelined edges

        val physicalOps: Set[PhysicalOp] = operators.map(physicalPlan.getOperator)

        // Enumerate all ports belonging to the Region
        val ports: Set[GlobalPortIdentity] = physicalOps.flatMap { op =>
          op.inputPorts.keys
            .map(inputPortId => GlobalPortIdentity(op.id, inputPortId, input = true))
            .toSet ++ op.outputPorts.keys
            .map(outputPortId => GlobalPortIdentity(op.id, outputPortId))
            .toSet
        }

        // Build the Region skeleton; cache/resourceConfig to be populated after classification.
        Region(
          id = RegionIdentity(idx),
          physicalOps = physicalOps,
          physicalLinks = links,
          ports = ports,
          resourceConfig = None,
          cached = false
        )
    }

    // Regions are returned as skeletons; cache/URI assignment happens in annotateRegionsWithCacheInfo.
    regionSkeletons
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
    createRegions(physicalPlan, matEdges).foreach(region => {
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
    if (isAcyclic) {
      annotateRegionsWithCacheInfo(regionDAG, matEdges, opToRegionMap.toMap)
      Left(regionDAG)
    }
    else Right(regionGraph)
  }

  /**
    * Performs a search to generate a region DAG.
    * Materializations are added only after the plan is determined to be schedulable.
    *
    * @return A region DAG.
    */
  private def createRegionDAG(): DirectedAcyclicGraph[Region, RegionLink] = {
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

    val regionDAG = searchResult.regionDAG
    regionDAG
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
        physicalPlan.getNonBridgeNonBlockingLinks
      } else {
        physicalPlan.links.diff(
          physicalPlan.getBlockingAndDependeeLinks
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
          physicalPlan.getBlockingAndDependeeLinks ++ currentState
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
          currentState ++ physicalPlan.getBlockingAndDependeeLinks
        // Generate and enqueue all neighbour states that haven't been visited
        var candidateEdges = originalNonBlockingEdges
          .diff(currentState)
        if (oChains) {
          val edgesInChainWithMaterializedEdges = physicalPlan.maxChains
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
                physicalPlan.getBlockingAndDependeeLinks ++ neighborState
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
      tryConnectRegionDAG(physicalPlan.links) match {
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
      oCleanEdges: Boolean = true
  ): SearchResult = {
    val startTime = System.nanoTime()
    // Starting from a state where all non-blocking edges are materialized
    val originalSeedState = physicalPlan.links.diff(
      physicalPlan.getBlockingAndDependeeLinks
    )

    // Chain optimization: an edge in the same chain as a blocking edge should not be materialized
    val seedStateOptimizedByChainsIfApplicable = if (oChains) {
      val edgesInChainWithBlockingEdge = physicalPlan.maxChains
        .filter(chain => chain.intersect(physicalPlan.getBlockingAndDependeeLinks).nonEmpty)
        .flatten
      originalSeedState.diff(edgesInChainWithBlockingEdge)
    } else {
      originalSeedState
    }

    // Clean edge optimization: a clean edge should not be materialized
    val finalSeedState = if (oCleanEdges) {
      seedStateOptimizedByChainsIfApplicable.intersect(physicalPlan.getNonBridgeNonBlockingLinks)
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
        physicalPlan.getBlockingAndDependeeLinks ++ currentState
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
                physicalPlan.getBlockingAndDependeeLinks ++ neighborState
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
