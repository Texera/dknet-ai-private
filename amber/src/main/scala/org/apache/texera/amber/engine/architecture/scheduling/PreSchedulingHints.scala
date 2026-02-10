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

import org.apache.texera.amber.core.workflow.GlobalPortIdentity
import org.apache.texera.amber.engine.architecture.scheduling.config.OutputPortConfig

import java.net.URI

/**
  * Optional external planning hints for CostBasedScheduleGenerator.
  *
  * These hints are generic URI/config overrides and do not encode any cache-specific decisions.
  * They are intended to be prepared by a pre-scheduling step and consumed during region creation
  * before resource allocation/search evaluation.
  *
  * @param outputPortConfigOverrides output-port configs to override default writer URI allocation.
  *                                  This can be used for reuse-only outputs (`materialize = false`).
  * @param inputPortUriOverrides additional reader URIs for input ports, merged with scheduler-derived
  *                              materialized-edge input URIs.
  */
case class PreSchedulingHints(
    outputPortConfigOverrides: Map[GlobalPortIdentity, OutputPortConfig] = Map.empty,
    inputPortUriOverrides: Map[GlobalPortIdentity, List[URI]] = Map.empty
)
