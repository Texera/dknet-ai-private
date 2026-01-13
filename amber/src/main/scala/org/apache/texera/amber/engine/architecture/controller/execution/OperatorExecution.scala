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

package org.apache.texera.amber.engine.architecture.controller.execution

import org.apache.texera.amber.core.virtualidentity.ActorVirtualIdentity
import org.apache.texera.amber.core.workflow.PortIdentity
import org.apache.texera.amber.engine.architecture.controller.execution.ExecutionUtils.aggregateStates
import org.apache.texera.amber.engine.architecture.deploysemantics.layer.WorkerExecution
import org.apache.texera.amber.engine.architecture.rpc.controlreturns.WorkflowAggregatedState
import org.apache.texera.amber.engine.architecture.worker.statistics.{
  PortTupleMetricsMapping,
  WorkerState
}
import org.apache.texera.amber.engine.common.executionruntimestate.{
  OperatorMetrics,
  OperatorStatistics
}

import java.util
import scala.jdk.CollectionConverters._

case class OperatorExecution() {

  private val workerExecutions =
    new util.concurrent.ConcurrentHashMap[ActorVirtualIdentity, WorkerExecution]()
  // Cached metrics for ToSkip regions; when set, operator stats/state are derived from this only.
  private var cachedMetrics: Option[OperatorMetrics] = None

  /**
    * Sets cached operator metrics for a ToSkip region and bypasses worker-based aggregation.
    */
  def setCachedMetrics(metrics: OperatorMetrics): Unit = {
    cachedMetrics = Some(metrics)
  }

  /**
    * Clears cached operator metrics so the operator can report live worker stats again.
    */
  def clearCachedMetrics(): Unit = {
    cachedMetrics = None
  }

  /**
    * Initializes a `WorkerExecution` for the specified workerId and adds it to the workerExecutions map.
    * If a `WorkerExecution` for the given workerId already exists, an AssertionError is thrown.
    * After successfully adding the new `WorkerExecution`, it retrieves and returns the newly added instance.
    *
    * @param workerId The `ActorVirtualIdentity` representing the unique identity of the worker.
    * @return The `WorkerExecution` instance associated with the specified workerId.
    * @throws AssertionError if a `WorkerExecution` already exists for the given workerId.
    */
  def initWorkerExecution(workerId: ActorVirtualIdentity): WorkerExecution = {
    assert(
      !workerExecutions.contains(workerId),
      s"WorkerExecution already exists for workerId: $workerId"
    )
    workerExecutions.put(workerId, WorkerExecution())
    getWorkerExecution(workerId)
  }

  /**
    * Retrieves the `WorkerExecution` instance associated with the specified workerId.
    */
  def getWorkerExecution(workerId: ActorVirtualIdentity): WorkerExecution =
    workerExecutions.get(workerId)

  /**
    * Retrieves the set of all workerIds for which `WorkerExecution` instances have been initialized.
    */
  def getWorkerIds: Set[ActorVirtualIdentity] = workerExecutions.keys.asScala.toSet

  /**
    * Returns the aggregated operator state from worker executions, or the cached state when present.
    */
  def getState: WorkflowAggregatedState = {
    cachedMetrics
      .map(_.operatorState)
      .getOrElse {
        val workerStates = workerExecutions.values.asScala.map(_.getState)
        aggregateStates(
          workerStates,
          WorkerState.COMPLETED,
          WorkerState.TERMINATED,
          WorkerState.RUNNING,
          WorkerState.UNINITIALIZED,
          WorkerState.PAUSED,
          WorkerState.READY
        )
      }
  }

  private[this] def computeOperatorPortStats(
      workerPortStats: Iterable[PortTupleMetricsMapping]
  ): Seq[PortTupleMetricsMapping] = {
    ExecutionUtils.aggregatePortMetrics(workerPortStats)
  }

  /**
    * Returns operator metrics aggregated from worker executions, or cached metrics when set.
    */
  def getStats: OperatorMetrics = {
    cachedMetrics.getOrElse {
      val workerRawStats = workerExecutions.values.asScala.map(_.getStats)
      val inputMetrics = workerRawStats.flatMap(_.inputTupleMetrics)
      val outputMetrics = workerRawStats.flatMap(_.outputTupleMetrics)
      OperatorMetrics(
        getState,
        OperatorStatistics(
          inputMetrics = computeOperatorPortStats(inputMetrics),
          outputMetrics = computeOperatorPortStats(outputMetrics),
          getWorkerIds.size,
          dataProcessingTime = workerRawStats.map(_.dataProcessingTime).sum,
          controlProcessingTime = workerRawStats.map(_.controlProcessingTime).sum,
          idleTime = workerRawStats.map(_.idleTime).sum
        )
      )
    }
  }

  /**
    * Returns true when all worker input ports are completed, or always true for cached operators.
    */
  def isInputPortCompleted(portId: PortIdentity): Boolean = {
    if (cachedMetrics.isDefined) {
      true
    } else {
      workerExecutions
        .values()
        .asScala
        .map(workerExecution => workerExecution.getInputPortExecution(portId))
        .forall(_.completed)
    }
  }

  /**
    * Returns true when all worker output ports are completed, or always true for cached operators.
    */
  def isOutputPortCompleted(portId: PortIdentity): Boolean = {
    if (cachedMetrics.isDefined) {
      true
    } else {
      workerExecutions
        .values()
        .asScala
        .map(workerExecution => workerExecution.getOutputPortExecution(portId))
        .forall(_.completed)
    }
  }
}
