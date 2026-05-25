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

import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
} from "@angular/core";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { NgIf } from "@angular/common";
import { RouterLink } from "@angular/router";
import { NzCardComponent } from "ng-zorro-antd/card";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzPopconfirmDirective } from "ng-zorro-antd/popconfirm";
import { NzTooltipModule } from "ng-zorro-antd/tooltip";
import { NzDropdownDirective, NzDropdownMenuComponent } from "ng-zorro-antd/dropdown";
import { NzMenuDirective, NzMenuItemComponent } from "ng-zorro-antd/menu";
import { DashboardEntry } from "../../../../type/dashboard-entry";
import { UserAvatarComponent } from "../../user-avatar/user-avatar.component";
import { DashboardEntryActionsService } from "../../../../service/user/dashboard-entry-actions.service";
import { HubService } from "../../../../../hub/service/hub.service";
import { AppSettings } from "../../../../../common/app-setting";
import { formatSize } from "../../../../../common/util/size-formatter.util";
import { formatCount, formatRelativeTime } from "../../../../../common/util/format.util";
import { isDefined } from "../../../../../common/util/predicate";
import { DASHBOARD_HUB_DATASET_RESULT_DETAIL, DASHBOARD_USER_DATASET } from "../../../../../app-routing.constant";

@UntilDestroy()
@Component({
  selector: "texera-dataset-card-item",
  templateUrl: "./user-dataset-card-item.component.html",
  styleUrls: ["./user-dataset-card-item.component.scss"],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgIf,
    RouterLink,
    NzCardComponent,
    NzIconDirective,
    NzPopconfirmDirective,
    NzTooltipModule,
    NzDropdownDirective,
    NzDropdownMenuComponent,
    NzMenuDirective,
    NzMenuItemComponent,
    UserAvatarComponent,
  ],
})
export class UserDatasetCardItemComponent implements OnChanges {
  @Input() editable = false;
  @Input() currentUid: number | undefined;
  @Output() deleted = new EventEmitter<void>();
  @Output() refresh = new EventEmitter<void>();

  private _entry?: DashboardEntry;
  @Input()
  get entry(): DashboardEntry {
    if (!this._entry) {
      throw new Error("entry property must be provided.");
    }
    return this._entry;
  }
  set entry(value: DashboardEntry) {
    this._entry = value;
  }

  entryLink: string[] = [];
  coverImageSrc: string = "";
  readonly defaultCover = "assets/card_background.jpg";
  likeCount = 0;
  viewCount = 0;
  isLiked = false;

  constructor(
    private entryActions: DashboardEntryActionsService,
    private hubService: HubService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes["entry"] || changes["currentUid"]) {
      this.initializeEntry();
    }
    if (changes["entry"]) {
      this.likeCount = this.entry.likeCount ?? 0;
      this.viewCount = this.entry.viewCount ?? 0;
      this.isLiked = this.entry.isLiked ?? false;
    }
  }

  private initializeEntry(): void {
    if (this.entry.type !== "dataset" || typeof this.entry.id !== "number") {
      return;
    }
    const owners = this.entry.accessibleUserIds;
    if (this.currentUid !== undefined && owners.includes(this.currentUid)) {
      this.entryLink = [DASHBOARD_USER_DATASET, String(this.entry.id)];
    } else {
      this.entryLink = [DASHBOARD_HUB_DATASET_RESULT_DETAIL, String(this.entry.id)];
    }
    this.coverImageSrc = this.entry.coverImageUrl
      ? `${AppSettings.getApiEndpoint()}/dataset/${this.entry.id}/cover`
      : this.defaultCover;
  }

  onCoverError(event: Event): void {
    const image = event.target as HTMLImageElement;
    image.onerror = null;
    image.src = this.defaultCover;
  }

  public async onClickOpenShareAccess(): Promise<void> {
    const modal = await this.entryActions.openShareAccess(this.entry);
    modal?.componentInstance?.refresh.pipe(untilDestroyed(this)).subscribe(() => this.refresh.emit());
  }

  public onClickDownload = (): void => {
    this.entryActions.download(this.entry);
  };

  toggleLike(): void {
    if (!isDefined(this.currentUid) || !isDefined(this.entry.id)) return;
    // Flip optimistically; reconcile or revert when the server responds.
    const previousLiked = this.isLiked;
    this.isLiked = !previousLiked;
    this.likeCount += previousLiked ? -1 : 1;
    this.cdr.markForCheck();

    this.hubService
      .toggleLike(this.entry.id, this.entry.type, previousLiked)
      .pipe(untilDestroyed(this))
      .subscribe({
        next: ({ liked, likeCount }) => {
          this.isLiked = liked;
          this.likeCount = likeCount;
          this.cdr.markForCheck();
        },
        error: () => {
          this.isLiked = previousLiked;
          this.likeCount += previousLiked ? 1 : -1;
          this.cdr.markForCheck();
        },
      });
  }

  get canDelete(): boolean {
    return this.entry.type === "dataset" && this.entry.dataset.isOwner;
  }

  formatSize = formatSize;
  formatCount = formatCount;
  formatRelativeTime = formatRelativeTime;
}
