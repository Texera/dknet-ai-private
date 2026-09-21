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

import { UntilDestroy } from "@ngneat/until-destroy";
import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  ViewChild,
} from "@angular/core";
import {
  DatasetFileNode,
  getRelativePathFromDatasetFileNode,
} from "../../../../../../common/type/datasetVersionFileTree";
import { ITreeOptions, TREE_ACTIONS, TreeModule } from "@ali-hm/angular-tree-component";
import { NgIf } from "@angular/common";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzSpaceCompactItemDirective } from "ng-zorro-antd/space";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";

const IMAGE_EXTENSIONS = [".jpg", ".jpeg", ".png", ".gif", ".webp"] as const;

// The library adds a 2px drop slot after every row, plus one extra leading
// slot before the first row.
const TREE_DROP_SLOT_HEIGHT_PX = 2;

// Container height cap; matches the pre-virtualization max-height.
const MAX_FILE_TREE_CONTAINER_HEIGHT_PX = 200;

// The library throttles viewport re-measures to one per 17ms, leading-edge
// only — a call inside the window is dropped and never re-fired, so
// re-measures must wait out the window.
const TREE_VIEWPORT_REMEASURE_DELAY_MS = 25;

// Total node count across the whole tree, including collapsed descendants.
function countNodes(nodes: DatasetFileNode[]): number {
  return nodes.reduce((count, node) => count + 1 + countNodes(node.children ?? []), 0);
}

@UntilDestroy()
@Component({
  selector: "texera-user-dataset-version-filetree",
  templateUrl: "./user-dataset-version-filetree.component.html",
  styleUrls: ["./user-dataset-version-filetree.component.scss"],
  imports: [
    TreeModule,
    NgIf,
    ɵNzTransitionPatchDirective,
    NzIconDirective,
    NzSpaceCompactItemDirective,
    NzButtonComponent,
    NzTooltipDirective,
  ],
})
export class UserDatasetVersionFiletreeComponent implements AfterViewInit, OnDestroy {
  @Input()
  public isTreeNodeDeletable: boolean = false;

  @Input()
  public isCoverSettable: boolean = false;

  @Input()
  public set fileTreeNodes(nodes: DatasetFileNode[]) {
    this._fileTreeNodes = nodes ?? [];
    const newHeight = this.computeContainerHeightPx();
    if (newHeight !== this.fileTreeContainerHeightPx) {
      this.fileTreeContainerHeightPx = newHeight;
      // The tree measures its viewport once after init and again only on
      // scroll, so a height change must trigger a re-measure (delayed past
      // the throttle) — otherwise a tree that starts empty stays blank. For
      // the same reason, a host that creates this component hidden must call
      // tree.sizeChanged() on reveal.
      setTimeout(() => this.tree?.sizeChanged(), TREE_VIEWPORT_REMEASURE_DELAY_MS);
    }
  }
  public get fileTreeNodes(): DatasetFileNode[] {
    return this._fileTreeNodes;
  }
  private _fileTreeNodes: DatasetFileNode[] = [];

  @Input()
  public isExpandAllAfterViewInit = false;

  @ViewChild("tree") tree: any;
  @ViewChild("fileTreeContainer") private fileTreeContainer?: ElementRef<HTMLElement>;

  // Set once the container has actually been laid out with a non-zero height.
  private viewportMeasured = false;
  private containerResizeObserver?: ResizeObserver;

  @Output()
  setCoverImage = new EventEmitter<string>();

  // Row height used by the virtual scroll; the template binds it as
  // --tree-node-height so the SCSS row rules stay in sync with nodeHeight.
  public readonly TREE_NODE_HEIGHT_PX = 24;

  // min(content, 200px); bound in the template so small trees hug their
  // content while the virtual scroll keeps a definite viewport.
  public fileTreeContainerHeightPx = 0;

  // useVirtualScroll keeps only the visible rows in the DOM; without it,
  // hundreds of files freeze the page for seconds to minutes.
  public fileTreeDisplayOptions: ITreeOptions = {
    displayField: "name",
    hasChildrenField: "children",
    useVirtualScroll: true,
    nodeHeight: this.TREE_NODE_HEIGHT_PX,
    actionMapping: {
      mouse: {
        click: (tree: any, node: any, $event: any) => {
          if (node.hasChildren) {
            TREE_ACTIONS.TOGGLE_EXPANDED(tree, node, $event);
          } else {
            this.selectedTreeNode.emit(node.data);
          }
        },
      },
    },
  };

  @Output()
  public selectedTreeNode = new EventEmitter<DatasetFileNode>();

  @Output()
  public deletedTreeNode = new EventEmitter<DatasetFileNode>();

  constructor() {}

  onNodeDeleted(node: DatasetFileNode): void {
    this.deletedTreeNode.emit(node);
  }

  ngAfterViewInit(): void {
    if (this.isExpandAllAfterViewInit) {
      this.tree.treeModel.expandAll();
    }
    this.observeContainerForFirstLayout();
  }

  ngOnDestroy(): void {
    this.containerResizeObserver?.disconnect();
  }

  /**
   * `useVirtualScroll` renders only the rows it believes fall inside a measured
   * viewport, and the tree measures that viewport once after init. A host that
   * reveals this component asynchronously -- an `nz-collapse-panel` animating
   * open, a tab becoming active -- is still zero-height at that moment, so every
   * row is culled and the tree stays blank even though the data arrived. The
   * fixed re-measure timer in the `fileTreeNodes` setter can lose the same race.
   *
   * Watching the container instead of guessing a delay: the first time it has a
   * real height, re-measure. Cheap, and it covers every host.
   */
  private observeContainerForFirstLayout(): void {
    const container = this.fileTreeContainer?.nativeElement;
    if (!container || typeof ResizeObserver === "undefined") {
      return;
    }
    this.containerResizeObserver = new ResizeObserver(() => {
      const hasLayout = container.clientHeight > 0 && container.clientWidth > 0;
      if (hasLayout && !this.viewportMeasured) {
        this.viewportMeasured = true;
        this.tree?.sizeChanged();
      } else if (!hasLayout) {
        // Hidden again (panel collapsed); re-measure next time it is revealed.
        this.viewportMeasured = false;
      }
    });
    this.containerResizeObserver.observe(container);
  }

  isImageFile(fileName: string): boolean {
    return IMAGE_EXTENSIONS.some(ext => fileName.toLowerCase().endsWith(ext));
  }

  // countNodes includes collapsed descendants, so a partially collapsed tree
  // may get a slightly taller container — still bounded by the cap.
  private computeContainerHeightPx(): number {
    const nodeCount = countNodes(this._fileTreeNodes);
    if (nodeCount === 0) {
      return 0;
    }
    const contentHeightPx =
      nodeCount * (this.TREE_NODE_HEIGHT_PX + TREE_DROP_SLOT_HEIGHT_PX) + TREE_DROP_SLOT_HEIGHT_PX;
    return Math.min(contentHeightPx, MAX_FILE_TREE_CONTAINER_HEIGHT_PX);
  }

  onSetCover(nodeData: DatasetFileNode): void {
    this.setCoverImage.emit(getRelativePathFromDatasetFileNode(nodeData));
  }
}
