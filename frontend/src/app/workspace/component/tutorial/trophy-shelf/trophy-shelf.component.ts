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

import { Component, EventEmitter, Input, Output, OnChanges } from "@angular/core";
import { DatePipe, NgClass, NgFor, NgIf } from "@angular/common";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { ArgusComponent } from "../argus/argus.component";
import { BONUS_BADGES, BadgeDef, FLOWS, TutorialFlow } from "../../../service/tutorial/flows";
import type { TutorialProgress } from "../../../service/tutorial/tutorial-progress";

interface BadgeRow {
  badge: BadgeDef;
  earned: boolean;
  earnedAt?: number;
}

@Component({
  selector: "texera-trophy-shelf",
  templateUrl: "trophy-shelf.component.html",
  styleUrls: ["trophy-shelf.component.scss"],
  imports: [
    NgIf,
    NgFor,
    NgClass,
    DatePipe,
    NzButtonComponent,
    NzWaveDirective,
    ɵNzTransitionPatchDirective,
    ArgusComponent,
  ],
})
export class TrophyShelfComponent implements OnChanges {
  @Input() open = false;
  @Input() progress: TutorialProgress | null = null;
  @Input() flows: TutorialFlow[] = FLOWS;
  @Output() closed = new EventEmitter<void>();
  @Output() startFlow = new EventEmitter<string>();

  rows: BadgeRow[] = [];
  /** Badge ids whose tile is currently flipped to show the back. */
  flipped = new Set<string>();

  ngOnChanges(): void {
    this.rows = this.buildRows();
  }

  toggleFlip(badgeId: string): void {
    if (this.flipped.has(badgeId)) {
      this.flipped.delete(badgeId);
    } else {
      this.flipped.add(badgeId);
    }
  }

  get earnedCount(): number {
    return this.rows.filter(r => r.earned).length;
  }

  get totalCount(): number {
    return this.rows.length;
  }

  get xp(): number {
    return this.progress?.xp ?? 0;
  }

  onClose(): void {
    this.closed.emit();
  }

  onStartFlow(flowId: string): void {
    this.startFlow.emit(flowId);
  }

  private buildRows(): BadgeRow[] {
    const earned = new Set(this.progress?.earnedBadges ?? []);
    const completed = this.progress?.completed ?? {};
    const rows: BadgeRow[] = [];

    for (const flow of this.flows) {
      const completion = completed[flow.id];
      rows.push({
        badge: flow.badge,
        earned: earned.has(flow.badge.id),
        earnedAt: completion?.completedAt,
      });
    }
    for (const bonus of BONUS_BADGES) {
      rows.push({ badge: bonus, earned: earned.has(bonus.id) });
    }
    return rows;
  }
}
