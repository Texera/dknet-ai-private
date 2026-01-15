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

import org.apache.texera.amber.engine.architecture.rpc.controlreturns.WorkflowAggregatedState
import org.apache.texera.amber.engine.architecture.worker.statistics.{
  PortTupleMetricsMapping,
  TupleMetrics
}
import org.apache.texera.amber.engine.common.executionruntimestate.{
  OperatorMetrics,
  OperatorStatistics
}

object ExecutionUtils {

  /**
    * Handle the case when a logical operator has two physical operators within a same region (e.g., Aggregate operator)
    */
  def aggregateMetrics(metrics: Iterable[OperatorMetrics]): OperatorMetrics = {
    if (metrics.isEmpty) {
      // Return a default OperatorMetrics if metrics are empty
      return OperatorMetrics(
        WorkflowAggregatedState.UNINITIALIZED,
        OperatorStatistics(Seq.empty, Seq.empty, 0, 0, 0, 0)
      )
    }

    val aggregatedState = aggregateStates(
      metrics.map(_.operatorState),
      WorkflowAggregatedState.COMPLETED,
      WorkflowAggregatedState.TERMINATED,
      WorkflowAggregatedState.RUNNING,
      WorkflowAggregatedState.UNINITIALIZED,
      WorkflowAggregatedState.PAUSED,
      WorkflowAggregatedState.READY,
      Some(WorkflowAggregatedState.COMPLETED_FROM_CACHE)
    )

    def sumMetrics(
        extractor: OperatorMetrics => Iterable[PortTupleMetricsMapping]
    ): Seq[PortTupleMetricsMapping] = {
      val filteredMetrics = metrics.flatMap(extractor).filterNot(_.portId.internal)
      aggregatePortMetrics(filteredMetrics)
    }

    val inputMetricsSum = sumMetrics(_.operatorStatistics.inputMetrics)
    val outputMetricsSum = sumMetrics(_.operatorStatistics.outputMetrics)

    val numWorkersSum = metrics.map(_.operatorStatistics.numWorkers).sum
    val dataProcessingTimeSum = metrics.map(_.operatorStatistics.dataProcessingTime).sum
    val controlProcessingTimeSum = metrics.map(_.operatorStatistics.controlProcessingTime).sum
    val idleTimeSum = metrics.map(_.operatorStatistics.idleTime).sum

    OperatorMetrics(
      aggregatedState,
      OperatorStatistics(
        inputMetricsSum,
        outputMetricsSum,
        numWorkersSum,
        dataProcessingTimeSum,
        controlProcessingTimeSum,
        idleTimeSum
      )
    )
  }

  /**
    * Aggregates execution states into a workflow-level state.
    *
    * Priority rules:
    * - Empty => UNINITIALIZED
    * - All completed or all terminated => COMPLETED
    * - All cached (if provided) => COMPLETED_FROM_CACHE
    * - Any running => RUNNING
    * - Otherwise, strip terminal states (completed/terminated/cached):
    *   - None left => COMPLETED (mixed terminal states)
    *   - All uninitialized => UNINITIALIZED
    *   - All paused => PAUSED
    *   - All ready => RUNNING
    *   - Else => UNKNOWN
    */
  def aggregateStates[T](
      states: Iterable[T],
      completedState: T,
      terminatedState: T,
      runningState: T,
      uninitializedState: T,
      pausedState: T,
      readyState: T,
      cachedState: Option[T] = None
  ): WorkflowAggregatedState = {
    states match {
      case _ if states.isEmpty                      => WorkflowAggregatedState.UNINITIALIZED
      case _ if states.forall(_ == completedState)  => WorkflowAggregatedState.COMPLETED
      case _ if states.forall(_ == terminatedState) => WorkflowAggregatedState.COMPLETED
      case _ if cachedState.isDefined && states.forall(_ == cachedState.get) =>
        WorkflowAggregatedState.COMPLETED_FROM_CACHE
      case _ if states.exists(_ == runningState) => WorkflowAggregatedState.RUNNING
      case _ =>
        val terminalStates =
          Set(completedState, terminatedState) ++ cachedState.toSet
        val unCompletedStates = states.filterNot(terminalStates.contains)
        if (unCompletedStates.isEmpty) {
          WorkflowAggregatedState.COMPLETED
        } else if (unCompletedStates.forall(_ == uninitializedState)) {
          WorkflowAggregatedState.UNINITIALIZED
        } else if (unCompletedStates.forall(_ == pausedState)) {
          WorkflowAggregatedState.PAUSED
        } else if (unCompletedStates.forall(_ == readyState)) {
          WorkflowAggregatedState.RUNNING
        } else {
          WorkflowAggregatedState.UNKNOWN
        }
    }
  }

  /**
    * Aggregates tuple metrics per port, preserving unknown markers for cached inputs.
    *
    * A negative count/size indicates an unknown value (e.g., cached input ports),
    * so any unknown value keeps the aggregated port metrics unknown.
    */
  def aggregatePortMetrics(
      metrics: Iterable[PortTupleMetricsMapping]
  ): Seq[PortTupleMetricsMapping] = {
    metrics
      .groupBy(_.portId)
      .view
      .map {
        case (portId, mappings) =>
          val hasUnknown =
            mappings.exists(m => m.tupleMetrics.count < 0 || m.tupleMetrics.size < 0)
          val totalCount =
            if (hasUnknown) -1L else mappings.map(_.tupleMetrics.count).sum
          val totalSize =
            if (hasUnknown) -1L else mappings.map(_.tupleMetrics.size).sum
          PortTupleMetricsMapping(portId, TupleMetrics(totalCount, totalSize))
      }
      .toSeq
  }
}
