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

package org.apache.texera.amber.core.workflow

import org.apache.texera.config.GuiConfig

import org.apache.amber.core.virtualidentity.ExecutionIdentity

import java.net.URI

case class CachedOutput(
    resultUri: URI,
    fingerprintJson: String,
    tupleCount: Option[Long],
    sourceExecutionId: Option[ExecutionIdentity]
)

case class WorkflowSettings(
    dataTransferBatchSize: Int = 400,
    outputPortsNeedingStorage: Set[GlobalPortIdentity] = Set.empty,
    // serialized GlobalPortIdentity -> cached output
    cachedOutputs: Map[String, CachedOutput] = Map.empty
)
