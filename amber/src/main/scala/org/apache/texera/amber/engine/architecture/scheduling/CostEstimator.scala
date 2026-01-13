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

import org.apache.amber.core.virtualidentity.ActorVirtualIdentity
import org.apache.amber.core.workflow.WorkflowContext
import org.apache.amber.engine.architecture.scheduling.DefaultCostEstimator.DEFAULT_OPERATOR_COST
import org.apache.amber.engine.architecture.scheduling.config.{
  InputPortConfig,
  IntermediateInputPortConfig,
  OutputPortConfig,
  ResourceConfig
}
import org.apache.amber.engine.architecture.scheduling.resourcePolicies.ResourceAllocator
import org.apache.amber.engine.common.AmberLogging

/**
  * A cost estimator should estimate a cost of running a region under the given resource constraints as units.
  */
trait CostEstimator {

  /**
    * Uses the given resource units to allocate resources to the region, and determine a cost based on the allocation.
    *
    * Note currently the ResourceAllocator is not cost-based and thus we use a cost model that does not rely on the
    * allocator, i.e., the cost estimation process is external to the ResourceAllocator.
    * @return A ResourceConfig for the region and an estimated cost of this region.
    */
  def allocateResourcesAndEstimateCost(region: Region, resourceUnits: Int): (ResourceConfig, Double)
}

object DefaultCostEstimator {
  val DEFAULT_OPERATOR_COST: Double = 1.0
}

/**
  * A default cost estimator using past statistics. If past statistics of a workflow are available, the cost of a region
  * is the execution time of its longest-running operator. Otherwise the cost is the number of materialized ports in the
  * region.
  */
class DefaultCostEstimator(
    workflowContext: WorkflowContext,
    val resourceAllocator: ResourceAllocator,
    val actorId: ActorVirtualIdentity
) extends CostEstimator
    with AmberLogging {

  override def allocateResourcesAndEstimateCost(
      region: Region,
      resourceUnits: Int
  ): (ResourceConfig, Double) = {
    // Currently the dummy cost from resourceAllocator is discarded.
    val (resourceConfig, _) = resourceAllocator.allocate(region)
    val cost =
      if (region.cached) {
        0.0
      } else {
        val opCost = region.getOperators.size * DEFAULT_OPERATOR_COST
        val writeCost = resourceConfig.portConfigs.values.collect {
          case _: OutputPortConfig =>
            0.5
        }.sum
        val readCost = resourceConfig.portConfigs.values.collect {
          case _: InputPortConfig             => 0.5
          case _: IntermediateInputPortConfig => 0.5
        }.sum
        opCost + writeCost + readCost
      }
    (resourceConfig, cost)
  }
}
