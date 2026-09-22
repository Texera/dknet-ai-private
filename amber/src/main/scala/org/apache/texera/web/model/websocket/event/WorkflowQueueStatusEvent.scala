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

package org.apache.texera.web.model.websocket.event

/**
  * Where this workflow stands in its public computing unit's run queue.
  *
  * Sent to every session watching the workflow, not just the one that pressed Run, so a
  * collaborator sees the same wait. Deliberately not a [[WorkflowStateEvent]]: queueing happens
  * before an execution exists, and WorkflowAggregatedState is the engine's own state, persisted
  * through maptoStatusCode. A queued run has no engine yet and must not reach workflow_executions.
  *
  * @param queued      false once the run has been admitted; the usual execution events take over
  * @param position    1-based place in the queue, 0 when not queued
  * @param queueLength how many runs are waiting, this one included
  */
case class WorkflowQueueStatusEvent(
    queued: Boolean,
    position: Int,
    queueLength: Int
) extends TexeraWebSocketEvent
