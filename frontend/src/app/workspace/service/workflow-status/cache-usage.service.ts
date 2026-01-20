/**
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

import { Injectable } from "@angular/core";
import { BehaviorSubject, Observable } from "rxjs";
import { CachedPortUsage } from "../../types/workflow-websocket.interface";
import { WorkflowWebsocketService } from "../workflow-websocket/workflow-websocket.service";

/**
 * Stores cache usage metadata emitted for the current execution and exposes helpers
 * for rendering per-port cache labels and cache entry highlights.
 */
@Injectable({
  providedIn: "root",
})
export class CacheUsageService {
  private readonly cacheUsageSubject = new BehaviorSubject<ReadonlyArray<CachedPortUsage>>([]);

  constructor(private workflowWebsocketService: WorkflowWebsocketService) {
    this.registerCacheUsageListener();
  }

  /**
   * Returns a stream of cached output metadata matched for the current execution.
   */
  public getCacheUsageStream(): Observable<ReadonlyArray<CachedPortUsage>> {
    return this.cacheUsageSubject.asObservable();
  }

  /**
   * Returns the latest cached output metadata snapshot.
   */
  public getCacheUsageSnapshot(): ReadonlyArray<CachedPortUsage> {
    return this.cacheUsageSubject.value;
  }

  /**
   * Builds label text per output port for a logical operator, keyed by port id.
   * Each label is a single line (e.g., "src 42") that is rendered below the port count.
   */
  public getPortCacheLabels(operatorId: string): Record<string, string> {
    const labels: Record<string, string> = {};
    this.cacheUsageSubject.value
      .filter(entry => entry.logicalOpId === operatorId)
      .forEach(entry => {
        const portKey = entry.portId.toString();
        const executionId = entry.sourceExecutionId ?? "unknown";
        labels[portKey] = `src ${executionId}`;
      });
    return labels;
  }

  /**
   * Builds a stable key to match cache entries against cache usage updates.
   * Includes the source execution to avoid tagging replaced cache entries as usable.
   */
  public buildUsageKey(globalPortId: string, subdagHash: string, sourceExecutionId?: number): string {
    const executionToken = sourceExecutionId == null ? "unknown" : sourceExecutionId.toString();
    return `${globalPortId}|${subdagHash}|${executionToken}`;
  }

  private registerCacheUsageListener(): void {
    this.workflowWebsocketService.subscribeToEvent("CacheUsageUpdateEvent").subscribe(event => {
      this.cacheUsageSubject.next(event.cachedOutputs);
    });
  }
}
