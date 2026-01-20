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

import { Component, OnInit } from "@angular/core";
import { ActivatedRoute } from "@angular/router";
import { finalize, tap } from "rxjs/operators";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { WorkflowCacheEntry } from "../../../../dashboard/type/workflow-cache-entry";
import { CacheUsageService } from "../../../service/workflow-status/cache-usage.service";
import {
  CacheInvalidationNotice,
  CacheManualClearNotice,
  WorkflowCacheEntriesService,
} from "../../../service/workflow-status/workflow-cache-entries.service";

/**
 * CachePanelComponent renders cache entry metadata for the current workflow.
 */
@UntilDestroy()
@Component({
  selector: "texera-cache-panel",
  templateUrl: "cache-panel.component.html",
  styleUrls: ["cache-panel.component.scss"],
})
export class CachePanelComponent implements OnInit {
  public cacheEntries: WorkflowCacheEntry[] = [];
  /** Entries shown in the table after applying the usable-only toggle. */
  public visibleEntries: WorkflowCacheEntry[] = [];
  /** When true, only cache entries usable by the current execution are shown. */
  public showUsableOnly = false;
  /** True while the cache eviction request is in flight. */
  public removing = false;
  public loading = false;
  /** Latest auto-invalidation notice shown in the cache panel. */
  public invalidationNotice?: CacheInvalidationNotice;
  /** Latest manual cache-clear notice shown in the cache panel. */
  public manualClearNotice?: CacheManualClearNotice;
  private workflowId?: number;
  private usageKeys = new Set<string>();

  constructor(
    private cacheEntriesService: WorkflowCacheEntriesService,
    private cacheUsageService: CacheUsageService,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    const workflowId = Number(this.route.snapshot.params.id);
    if (!workflowId) {
      return;
    }
    this.workflowId = workflowId;
    this.cacheEntriesService
      .getCacheEntriesStream()
      .pipe(untilDestroyed(this))
      .subscribe(entries => {
        this.cacheEntries = [...entries];
        this.updateVisibleEntries();
      });
    this.cacheEntriesService
      .getLoadingStream()
      .pipe(untilDestroyed(this))
      .subscribe(loading => {
        this.loading = loading;
      });
    this.refresh();
    this.cacheUsageService
      .getCacheUsageStream()
      .pipe(untilDestroyed(this))
      .subscribe(entries => {
        this.usageKeys = new Set(
          entries.map(entry => this.cacheUsageService.buildUsageKey(entry.globalPortId, entry.subdagHash))
        );
        this.updateVisibleEntries();
      });
    this.cacheEntriesService
      .getInvalidationNoticeStream()
      .pipe(untilDestroyed(this))
      .subscribe(notice => {
        if (notice && this.workflowId && notice.workflowId === this.workflowId) {
          this.invalidationNotice = notice;
        } else if (!notice) {
          this.invalidationNotice = undefined;
        }
      });
    this.cacheEntriesService
      .getManualClearNoticeStream()
      .pipe(untilDestroyed(this))
      .subscribe(notice => {
        if (notice && this.workflowId && notice.workflowId === this.workflowId) {
          this.manualClearNotice = notice;
        } else if (!notice) {
          this.manualClearNotice = undefined;
        }
      });
  }

  /**
   * Refreshes the cache entry list from the backend.
   */
  public refresh(): void {
    if (!this.workflowId) {
      return;
    }
    this.cacheEntriesService.refreshCacheEntries(this.workflowId).pipe(untilDestroyed(this)).subscribe();
  }

  /**
   * Removes all cached outputs for the workflow, updates shared cache state,
   * and shows a cache-clear notification on success.
   */
  public clearCacheEntries(): void {
    if (!this.workflowId) {
      return;
    }
    const removedCount = this.cacheEntries.length;
    this.removing = true;
    this.cacheEntriesService
      .clearWorkflowCacheEntries(this.workflowId)
      .pipe(
        tap(() => {
          const entryLabel = removedCount === 1 ? "entry" : "entries";
          const message = removedCount === 0 ? "Cache cleared." : `Cleared ${removedCount} cache ${entryLabel}.`;
          this.cacheEntriesService.notifyManualClear({
            workflowId: this.workflowId!,
            message,
            removedCount,
            timestamp: new Date(),
          });
        }),
        finalize(() => {
          this.removing = false;
        }),
        untilDestroyed(this)
      )
      .subscribe();
  }

  /**
   * Returns true when a cache entry is usable by the current execution (fingerprint match),
   * regardless of whether the scheduler chooses to reuse it.
   */
  public isUsableForExecution(entry: WorkflowCacheEntry): boolean {
    return this.usageKeys.has(this.cacheUsageService.buildUsageKey(entry.globalPortId, entry.subdagHash));
  }

  /**
   * Updates the entries shown in the table based on the usable-only toggle.
   */
  public updateVisibleEntries(): void {
    this.visibleEntries = this.showUsableOnly
      ? this.cacheEntries.filter(entry => this.isUsableForExecution(entry))
      : this.cacheEntries;
  }

  /**
   * Formats tuple counts for display.
   */
  public formatTupleCount(tupleCount?: number): string {
    return tupleCount === undefined ? "-" : tupleCount.toString();
  }

  /**
   * Formats source execution IDs for display.
   */
  public formatSourceExecutionId(sourceExecutionId?: number): string {
    return sourceExecutionId === undefined ? "-" : sourceExecutionId.toString();
  }

  /**
   * Shortens subDAG hash for compact display.
   */
  public shortenSubdagHash(hash: string): string {
    return hash.length > 8 ? hash.slice(0, 8) : hash;
  }

  /**
   * Formats the auto-invalidation notification message.
   */
  public formatInvalidationMessage(notice: CacheInvalidationNotice): string {
    const entryLabel = notice.removedCount === 1 ? "entry" : "entries";
    return `Auto-removed ${notice.removedCount} cache ${entryLabel} after workflow changes.`;
  }

  /**
   * Formats the auto-invalidation notification timestamp for display.
   */
  public formatInvalidationTimestamp(notice: CacheInvalidationNotice): string {
    return `At ${notice.timestamp.toLocaleString()}`;
  }

  /**
   * Clears the cache invalidation notice from the panel.
   */
  public clearInvalidationNotice(): void {
    this.invalidationNotice = undefined;
    this.cacheEntriesService.clearInvalidationNotice();
  }

  /**
   * Formats the manual cache-clear notification message.
   */
  public formatManualClearMessage(notice: CacheManualClearNotice): string {
    return notice.message;
  }

  /**
   * Formats the manual cache-clear notification timestamp for display.
   */
  public formatManualClearTimestamp(notice: CacheManualClearNotice): string {
    return `At ${notice.timestamp.toLocaleString()}`;
  }

  /**
   * Clears the manual cache-clear notice from the panel.
   */
  public clearManualClearNotice(): void {
    this.manualClearNotice = undefined;
    this.cacheEntriesService.clearManualClearNotice();
  }
}
