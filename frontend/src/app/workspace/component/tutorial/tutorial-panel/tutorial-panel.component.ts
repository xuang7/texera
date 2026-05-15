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

import { Component, OnDestroy, OnInit } from "@angular/core";
import { NgIf } from "@angular/common";
import { Subject } from "rxjs";
import { takeUntil } from "rxjs/operators";
import confetti from "canvas-confetti";
import { driver, Driver } from "driver.js";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { TutorialService } from "../../../service/tutorial/tutorial.service";

const TUTORIAL_SEEN_KEY = "texera-tutorial-seen";

@Component({
  selector: "texera-tutorial-panel",
  templateUrl: "tutorial-panel.component.html",
  styleUrls: ["tutorial-panel.component.scss"],
  imports: [
    NgIf,
    NzButtonComponent,
    NzWaveDirective,
    ɵNzTransitionPatchDirective,
    NzIconDirective,
    NzTooltipDirective,
  ],
})
export class TutorialPanelComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private welcomeDriver: Driver | null = null;

  public isActive = false;
  public showCompletion = false;

  constructor(public tutorialService: TutorialService) {}

  ngOnInit(): void {
    this.tutorialService.isActive$.pipe(takeUntil(this.destroy$)).subscribe(active => {
      this.isActive = active;
    });

    this.tutorialService.completed$.pipe(takeUntil(this.destroy$)).subscribe(() => {
      try {
        localStorage.setItem(TUTORIAL_SEEN_KEY, "1");
      } catch {
        /* localStorage may be unavailable */
      }
      this.showCompletion = true;
      this.fireConfetti();
      setTimeout(() => (this.showCompletion = false), 6000);
    });

    if (!this.hasSeenTutorial()) {
      setTimeout(() => this.showWelcomePopover(), 800);
    }
  }

  onRestart(): void {
    this.tutorialService.restart();
  }

  /**
   * Celebrate tour completion with a short confetti burst from both bottom
   * corners. Two staggered cannons feel livelier than a single center blast
   * and keep the spotlight on the result panel readable.
   */
  private fireConfetti(): void {
    const duration = 1800;
    const end = Date.now() + duration;
    const colors = ["#1890ff", "#52c41a", "#faad14", "#eb2f96", "#722ed1"];

    const frame = () => {
      confetti({
        particleCount: 4,
        angle: 60,
        spread: 60,
        startVelocity: 55,
        origin: { x: 0, y: 0.85 },
        colors,
      });
      confetti({
        particleCount: 4,
        angle: 120,
        spread: 60,
        startVelocity: 55,
        origin: { x: 1, y: 0.85 },
        colors,
      });
      if (Date.now() < end) requestAnimationFrame(frame);
    };
    frame();
  }

  private hasSeenTutorial(): boolean {
    try {
      return localStorage.getItem(TUTORIAL_SEEN_KEY) === "1";
    } catch {
      return false;
    }
  }

  private markSeen(): void {
    try {
      localStorage.setItem(TUTORIAL_SEEN_KEY, "1");
    } catch {
      /* localStorage may be unavailable */
    }
  }

  /**
   * Show the first-time welcome as a centered driver.js popover so it shares
   * the spotlight aesthetic of the rest of the tour. The CTA acts as the
   * single "Done" button (since there's no element to advance from) and
   * starts the real 16-step tour; the X button is the polite opt-out.
   */
  private showWelcomePopover(): void {
    let userStartedTour = false;
    this.welcomeDriver = driver({
      showProgress: false,
      allowClose: true,
      overlayColor: "#000",
      overlayOpacity: 0.7,
      stagePadding: 0,
      popoverClass: "tutorial-welcome-popover",
      showButtons: ["next", "close"],
      doneBtnText: "Start building your first workflow ✨",
      onCloseClick: () => {
        this.markSeen();
        this.welcomeDriver?.destroy();
      },
      onNextClick: () => {
        userStartedTour = true;
        this.markSeen();
        this.welcomeDriver?.destroy();
      },
      onDestroyed: () => {
        const wasStart = userStartedTour;
        this.welcomeDriver = null;
        if (wasStart) setTimeout(() => this.tutorialService.start(), 250);
      },
      steps: [
        {
          popover: {
            title: "👋 Welcome to Texera!",
            description: `
              <div style="text-align:center; padding: 4px 0 8px">
                <div class="tutorial-welcome-emoji">🧪</div>
                <p style="font-size:15px; margin: 0 0 10px; line-height: 1.5">
                  <b>Texera</b> lets you build big-data workflows by
                  <i>dragging blocks</i> — no code required.
                </p>
                <p style="color:#555; margin: 0 0 6px; line-height: 1.5">
                  Take a friendly <b>2-minute tour</b> and you'll have a
                  real workflow running by the end. We'll celebrate together 🎉
                </p>
                <p style="color:#888; font-size:12px; margin: 8px 0 0">
                  You can relaunch the tour any time from the floating button.
                </p>
              </div>
            `,
          },
        },
      ],
    });
    this.welcomeDriver.drive();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.welcomeDriver?.destroy();
  }
}
