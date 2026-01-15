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
  * Cache usage metadata for a single output port matched during submission-time lookup.
  *
  * @param globalPortId Serialized GlobalPortIdentity string
  * @param logicalOpId Logical operator id owning the port
  * @param layerName Physical operator layer name
  * @param portId Output port id
  * @param internal Whether the port is internal
  * @param subdagHash SHA-256 hash of the upstream subDAG fingerprint
  * @param tupleCount Cached tuple count (optional)
  * @param sourceExecutionId Execution id that produced the cached output (optional)
  */
case class CachedPortUsage(
    globalPortId: String,
    logicalOpId: String,
    layerName: String,
    portId: Int,
    internal: Boolean,
    subdagHash: String,
    tupleCount: Option[Long],
    sourceExecutionId: Option[Long]
)

/**
  * Websocket event that surfaces cache metadata matched for the current execution.
  */
case class CacheUsageUpdateEvent(cachedOutputs: List[CachedPortUsage]) extends TexeraWebSocketEvent
