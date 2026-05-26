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

import { ComponentFixture, TestBed } from "@angular/core/testing";
import { HttpClientTestingModule } from "@angular/common/http/testing";
import { BrowserAnimationsModule } from "@angular/platform-browser/animations";
import { RouterTestingModule } from "@angular/router/testing";
import { of } from "rxjs";
import type { Mocked } from "vitest";

import { DatasetCardItemComponent } from "./dataset-card-item.component";
import { DashboardEntry } from "src/app/dashboard/type/dashboard-entry";
import { NzModalService } from "ng-zorro-antd/modal";
import { DatasetService } from "../../../service/user/dataset/dataset.service";
import { DownloadService } from "../../../service/user/download/download.service";
import { HubService } from "../../../../hub/service/hub.service";
import { UserService } from "../../../../common/service/user/user.service";
import { StubUserService } from "../../../../common/service/user/stub-user.service";
import { AppSettings } from "../../../../common/app-setting";
import { DASHBOARD_HUB_DATASET_RESULT_DETAIL, DASHBOARD_USER_DATASET } from "../../../../app-routing.constant";
import { commonTestProviders } from "../../../../common/testing/test-utils";

function makeDatasetEntry(overrides: Partial<any> = {}): DashboardEntry {
  // Only includes fields read by the component's logic; template fields are skipped
  return {
    type: "dataset",
    id: 42,
    accessibleUserIds: [1, 2],
    coverImageUrl: undefined,
    likeCount: 5,
    isLiked: false,
    ...overrides,
  } as unknown as DashboardEntry;
}

describe("DatasetCardItemComponent", () => {
  let component: DatasetCardItemComponent;
  let fixture: ComponentFixture<DatasetCardItemComponent>;
  let hubService: Mocked<HubService>;

  beforeEach(async () => {
    const hubServiceSpy = {
      toggleLike: vi.fn().mockReturnValue(of({ liked: true, likeCount: 7 })),
    };

    await TestBed.configureTestingModule({
      imports: [DatasetCardItemComponent, HttpClientTestingModule, BrowserAnimationsModule, RouterTestingModule],
      providers: [
        { provide: NzModalService, useValue: { create: vi.fn() } },
        { provide: DatasetService, useValue: { retrieveOwners: vi.fn().mockReturnValue(of([])) } },
        { provide: DownloadService, useValue: { downloadDataset: vi.fn().mockReturnValue(of(new Blob())) } },
        { provide: HubService, useValue: hubServiceSpy },
        { provide: UserService, useClass: StubUserService },
        ...commonTestProviders,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DatasetCardItemComponent);
    component = fixture.componentInstance;
    hubService = TestBed.inject(HubService) as unknown as Mocked<HubService>;
  });

  describe("entryLink", () => {
    it("routes to the private dataset page when the current user has access", () => {
      component.currentUid = 1;
      component.entry = makeDatasetEntry({ id: 99, accessibleUserIds: [1, 2] });
      component.ngOnChanges({ entry: { currentValue: component.entry } } as any);
      expect(component.entryLink).toEqual([DASHBOARD_USER_DATASET, "99"]);
    });

    it("routes to the hub detail page when the current user has no access", () => {
      component.currentUid = 5;
      component.entry = makeDatasetEntry({ id: 99, accessibleUserIds: [1, 2] });
      component.ngOnChanges({ entry: { currentValue: component.entry } } as any);
      expect(component.entryLink).toEqual([DASHBOARD_HUB_DATASET_RESULT_DETAIL, "99"]);
    });
  });

  describe("coverImageSrc", () => {
    it("falls back to the default cover when coverImageUrl is missing", () => {
      component.entry = makeDatasetEntry({ coverImageUrl: undefined });
      component.ngOnChanges({ entry: { currentValue: component.entry } } as any);
      expect(component.coverImageSrc).toBe(component.defaultCover);
    });

    it("builds the API URL when coverImageUrl is set", () => {
      component.entry = makeDatasetEntry({ id: 7, coverImageUrl: "v1/img.png" });
      component.ngOnChanges({ entry: { currentValue: component.entry } } as any);
      expect(component.coverImageSrc).toBe(`${AppSettings.getApiEndpoint()}/dataset/7/cover`);
    });
  });

  describe("toggleLike", () => {
    beforeEach(() => {
      component.currentUid = 1;
      component.entry = makeDatasetEntry();
      component.ngOnChanges({ entry: { currentValue: component.entry } } as any);
    });

    it("does nothing when the user is not signed in", () => {
      component.currentUid = undefined;
      component.toggleLike();
      expect(hubService.toggleLike).not.toHaveBeenCalled();
    });

    it("toggles to liked and reconciles state from the server", () => {
      component.isLiked = false;
      component.toggleLike();
      expect(hubService.toggleLike).toHaveBeenCalledWith(42, "dataset", false);
      expect(component.isLiked).toBe(true);
      expect(component.likeCount).toBe(7);
    });

    it("toggles to unliked and reconciles state from the server", () => {
      hubService.toggleLike.mockReturnValueOnce(of({ liked: false, likeCount: 6 }));
      component.isLiked = true;
      component.toggleLike();
      expect(hubService.toggleLike).toHaveBeenCalledWith(42, "dataset", true);
      expect(component.isLiked).toBe(false);
      expect(component.likeCount).toBe(6);
    });
  });
});
