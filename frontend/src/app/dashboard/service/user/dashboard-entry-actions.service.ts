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
import { NzModalRef, NzModalService } from "ng-zorro-antd/modal";
import { firstValueFrom } from "rxjs";
import { ShareAccessComponent } from "../../component/user/share-access/share-access.component";
import { DashboardEntry } from "../../type/dashboard-entry";
import { DatasetService } from "./dataset/dataset.service";
import { DownloadService } from "./download/download.service";

/** Dataset actions shared by dashboard list and card views. */
@Injectable({ providedIn: "root" })
export class DashboardEntryActionsService {
  constructor(
    private modalService: NzModalService,
    private datasetService: DatasetService,
    private downloadService: DownloadService
  ) {}

  async openShareAccess(entry: DashboardEntry): Promise<NzModalRef<ShareAccessComponent> | undefined> {
    if (entry.type !== "dataset") return undefined;
    return this.modalService.create({
      nzContent: ShareAccessComponent,
      nzData: {
        writeAccess: entry.accessLevel === "WRITE",
        type: "dataset",
        id: entry.id,
        allOwners: await firstValueFrom(this.datasetService.retrieveOwners()),
      },
      nzFooter: null,
      nzTitle: "Share this dataset with others",
      nzCentered: true,
      nzWidth: "700px",
    });
  }

  download(entry: DashboardEntry): void {
    if (entry.type !== "dataset" || !entry.id) return;
    this.downloadService.downloadDataset(entry.id, entry.name).subscribe();
  }
}
