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

package org.apache.texera.web.service

import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.amber.core.workflow.cache.FingerprintUtil
import org.apache.texera.amber.core.workflow.{GlobalPortIdentity, PhysicalPlan, WorkflowContext}
import org.apache.texera.amber.engine.architecture.controller.PortMaterialized
import org.apache.texera.amber.engine.common.client.AmberClient
import org.apache.texera.amber.util.serde.GlobalPortIdentitySerde.SerdeOps
import org.apache.texera.web.SubscriptionManager
import org.apache.texera.web.model.websocket.event.CacheEntryUpdateEvent
import org.apache.texera.web.storage.{ExecutionCacheEntryUpdateStore, ExecutionStateStore}

/**
  * Service that listens for port materialization events from the controller
  * and persists cache metadata. It also emits cache entry updates so the
  * frontend can refresh cache info as new cached results appear.
  *
  * @param client AmberClient to register callbacks
  * @param cacheService OperatorPortCacheService for cache persistence
  * @param workflowContext WorkflowContext for workflow/execution IDs
  * @param physicalPlan PhysicalPlan for fingerprint computation
  * @param executionStateStore Execution state store for cache entry updates
  */
class ExecutionCacheService(
    client: AmberClient,
    cacheService: OperatorPortCacheService,
    workflowContext: WorkflowContext,
    physicalPlan: PhysicalPlan,
    executionStateStore: ExecutionStateStore
) extends SubscriptionManager
    with LazyLogging {

  registerCallbacks()

  private def registerCallbacks(): Unit = {
    addSubscription(
      client
        .registerCallback[PortMaterialized]((evt: PortMaterialized) => {
          logger.info(
            s"Port materialized: ${evt.portId}, URI: ${evt.resultUri}, tuple count: ${evt.tupleCount}"
          )
          try {
            cacheService.upsertCachedOutput(
              workflowContext.workflowId,
              workflowContext.executionId,
              evt.portId,
              physicalPlan,
              evt.resultUri,
              evt.tupleCount
            )
            emitCacheEntryUpdate(evt.portId, evt.tupleCount)
          } catch {
            case e: Throwable =>
              logger.error(s"Failed to upsert cache for port ${evt.portId}", e)
          }
        })
    )
  }

  /**
    * Emits a cache entry update after a cache upsert so websocket clients
    * can refresh cache metadata for the current execution.
    */
  private def emitCacheEntryUpdate(
      portId: GlobalPortIdentity,
      tupleCount: Option[Long]
  ): Unit = {
    val fingerprint = FingerprintUtil.computeSubdagFingerprint(physicalPlan, portId)
    val updateEvent = CacheEntryUpdateEvent(
      globalPortId = portId.serializeAsString,
      subdagHash = fingerprint.subdagHash,
      tupleCount = tupleCount,
      sourceExecutionId = workflowContext.executionId.id
    )
    executionStateStore.cacheEntryUpdateStore.updateState(_ =>
      ExecutionCacheEntryUpdateStore(Some(updateEvent), System.currentTimeMillis())
    )
  }
}
