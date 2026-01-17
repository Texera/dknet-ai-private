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
import { finalize, tap } from "rxjs/operators";
import { WorkflowExecutionsService } from "../../../dashboard/service/user/workflow-executions/workflow-executions.service";
import { WorkflowCacheEntry } from "../../../dashboard/type/workflow-cache-entry";
import { WorkflowActionService } from "../workflow-graph/model/workflow-action.service";

/**
 * Shares workflow cache entry state across components and keeps it in sync with the backend.
 */
@Injectable({
  providedIn: "root",
})
export class WorkflowCacheEntriesService {
  private readonly cacheEntriesSubject = new BehaviorSubject<ReadonlyArray<WorkflowCacheEntry>>([]);
  private readonly loadingSubject = new BehaviorSubject<boolean>(false);
  private currentWorkflowId?: number;

  constructor(
    private workflowExecutionsService: WorkflowExecutionsService,
    private workflowActionService: WorkflowActionService
  ) {
    this.workflowActionService.workflowMetaDataChanged().subscribe(metadata => {
      const workflowId = metadata.wid ?? 0;
      if (workflowId <= 0) {
        this.currentWorkflowId = undefined;
        this.cacheEntriesSubject.next([]);
        return;
      }
      if (workflowId !== this.currentWorkflowId) {
        this.currentWorkflowId = workflowId;
        this.refreshCacheEntries(workflowId).subscribe();
      }
    });

    const initialMetadata = this.workflowActionService.getWorkflowMetadata();
    const initialWorkflowId = initialMetadata?.wid ?? 0;
    if (initialWorkflowId > 0 && initialWorkflowId !== this.currentWorkflowId) {
      this.currentWorkflowId = initialWorkflowId;
      this.refreshCacheEntries(initialWorkflowId).subscribe();
    }
  }

  /**
   * Returns a stream of cache entries for the active workflow.
   */
  public getCacheEntriesStream(): Observable<ReadonlyArray<WorkflowCacheEntry>> {
    return this.cacheEntriesSubject.asObservable();
  }

  /**
   * Returns a stream of cache entry loading state.
   */
  public getLoadingStream(): Observable<boolean> {
    return this.loadingSubject.asObservable();
  }

  /**
   * Returns the latest cache entry snapshot.
   */
  public getCacheEntriesSnapshot(): ReadonlyArray<WorkflowCacheEntry> {
    return this.cacheEntriesSubject.value;
  }

  /**
   * Refreshes cache entries for a workflow and updates shared state.
   *
   * @param workflowId Workflow ID to refresh entries for
   */
  public refreshCacheEntries(workflowId: number): Observable<WorkflowCacheEntry[]> {
    if (workflowId > 0 && workflowId !== this.currentWorkflowId) {
      this.currentWorkflowId = workflowId;
    }
    this.loadingSubject.next(true);
    return this.workflowExecutionsService.retrieveWorkflowCacheEntries(workflowId).pipe(
      tap(entries => this.cacheEntriesSubject.next(entries)),
      finalize(() => this.loadingSubject.next(false))
    );
  }

  /**
   * Clears all cached outputs for a workflow and updates shared state.
   *
   * @param workflowId Workflow ID whose cache entries should be removed
   */
  public clearWorkflowCacheEntries(workflowId: number): Observable<void> {
    return this.workflowExecutionsService.deleteWorkflowCacheEntries(workflowId).pipe(
      tap(() => this.cacheEntriesSubject.next([]))
    );
  }

  /**
   * Returns cached output port IDs for a logical operator.
   *
   * @param operatorId Logical operator ID
   */
  public getCachedOutputPorts(operatorId: string): Set<string> {
    const cachedPorts = new Set<string>();
    this.cacheEntriesSubject.value
      .filter(entry => entry.logicalOpId === operatorId && !entry.internal)
      .forEach(entry => cachedPorts.add(entry.portId.toString()));
    return cachedPorts;
  }
}
