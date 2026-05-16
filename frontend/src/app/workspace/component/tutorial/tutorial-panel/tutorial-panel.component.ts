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
import { BadgeDef, TutorialFlow, getFlowById } from "../../../service/tutorial/flows";
import { TutorialProgress } from "../../../service/tutorial/tutorial-progress";
import { BadgeUnlockedComponent } from "../badge-unlocked/badge-unlocked.component";
import { TrophyShelfComponent } from "../trophy-shelf/trophy-shelf.component";

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
    BadgeUnlockedComponent,
    TrophyShelfComponent,
  ],
})
export class TutorialPanelComponent implements OnInit, OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private welcomeDriver: Driver | null = null;
  private resumeDriver: Driver | null = null;
  private chainDriver: Driver | null = null;

  public isActive = false;
  public showCompletion = false;
  public progress: TutorialProgress | null = null;
  public trophyShelfOpen = false;
  public pendingBadge: BadgeDef | null = null;
  public pendingBadgeFlow = "";

  constructor(public tutorialService: TutorialService) {}

  ngOnInit(): void {
    this.tutorialService.isActive$.pipe(takeUntil(this.destroy$)).subscribe(active => {
      this.isActive = active;
    });

    this.tutorialService.progress$.pipe(takeUntil(this.destroy$)).subscribe(p => {
      this.progress = p;
    });

    this.tutorialService.completed$.pipe(takeUntil(this.destroy$)).subscribe(({ flowId }) => {
      this.fireConfetti();

      const finished = getFlowById(flowId);
      const nextFlow = finished?.chainTo ? getFlowById(finished.chainTo) : null;

      if (finished && nextFlow) {
        // Chain-able flow: merge the "you did it" celebration into the
        // chain-confirmation popover. No separate "Awesome!" toast.
        setTimeout(() => this.showChainConfirmPopover(finished, nextFlow), 400);
      } else {
        // Terminal flow: show the standard celebration toast.
        this.showCompletion = true;
        setTimeout(() => (this.showCompletion = false), 6000);
      }
    });

    this.tutorialService.badgeUnlocked$.pipe(takeUntil(this.destroy$)).subscribe(evt => {
      // Briefly defer so the badge card lands on top of the celebration toast,
      // not under it.
      setTimeout(() => {
        this.pendingBadge = evt.badge;
        this.pendingBadgeFlow = evt.flowName;
      }, 700);
    });

    setTimeout(() => this.openInitialPopover(), 800);
  }

  /**
   * On workspace mount, show one of:
   *  - flow picker (first-time user, no prior progress)
   *  - resume prompt (in-flight flow exists)
   *  - nothing (user already saw welcome and isn't mid-tour)
   */
  private openInitialPopover(): void {
    const p = this.progress ?? this.tutorialService.progress;
    if (p.current && getFlowById(p.current.flowId)?.steps.length) {
      this.showResumePopover();
    } else if (!p.seenWelcome) {
      this.showFlowPickerPopover();
    }
  }

  onLaunchPicker(): void {
    this.showFlowPickerPopover();
  }

  onTrophyClick(): void {
    this.trophyShelfOpen = true;
  }

  onTrophyClosed(): void {
    this.trophyShelfOpen = false;
  }

  onTrophyStartFlow(flowId: string): void {
    this.trophyShelfOpen = false;
    this.tutorialService.markWelcomeSeen();
    setTimeout(() => this.tutorialService.start(flowId), 200);
  }

  onBadgeDismissed(): void {
    this.pendingBadge = null;
  }

  onBadgeViewShelf(): void {
    this.pendingBadge = null;
    this.trophyShelfOpen = true;
  }

  // ===== Driver-based popovers =====

  private showFlowPickerPopover(): void {
    this.welcomeDriver = driver({
      showProgress: false,
      allowClose: true,
      overlayColor: "#000",
      overlayOpacity: 0.7,
      stagePadding: 0,
      popoverClass: "tutorial-welcome-popover tutorial-picker-popover",
      showButtons: ["close"],
      onCloseClick: () => {
        this.tutorialService.markWelcomeSeen();
        this.welcomeDriver?.destroy();
      },
      onDestroyed: () => {
        this.welcomeDriver = null;
      },
      steps: [
        {
          popover: {
            title: "👋 Welcome to Texera!",
            description: this.buildPickerHtml(),
          },
        },
      ],
    });
    this.welcomeDriver.drive();

    setTimeout(() => this.bindPickerHandlers(), 50);
  }

  private buildPickerHtml(): string {
    const flows = this.tutorialService.flows;
    const earnedBadges = new Set(this.progress?.earnedBadges ?? []);
    const cards = flows
      .map(flow => {
        const done = earnedBadges.has(flow.badge.id);
        const disabled = flow.comingSoon || flow.steps.length === 0;
        const meta = `${flow.estimatedMinutes} min · ${flow.difficulty}`;
        const ribbon = done
          ? "<span class=\"picker-ribbon done\">✓ Done</span>"
          : flow.comingSoon
            ? "<span class=\"picker-ribbon soon\">Coming soon</span>"
            : "";
        return `
          <button
            class="picker-flow-card${disabled ? " disabled" : ""}"
            ${disabled ? "disabled" : ""}
            data-flow-id="${flow.id}">
            <div class="picker-flow-header">
              <span class="picker-flow-emoji">${flow.badge.emoji}</span>
              <span class="picker-flow-name">${flow.name}</span>
              ${ribbon}
            </div>
            <div class="picker-flow-desc">${flow.shortDesc}</div>
            <div class="picker-flow-meta">${meta}</div>
          </button>
        `;
      })
      .join("");

    return `
      <div class="tutorial-picker">
        <div class="picker-intro">
          <p class="picker-tagline">
            Texera lets you build <b>data workflows visually</b> — drag operators (sources, filters, charts, ML models)
            onto a canvas, wire them together, and run the pipeline. <i>No code required.</i>
          </p>
          <p class="picker-cta">Ready to try? Pick a quick tour to get started.</p>
        </div>
        <div class="picker-list">${cards}</div>
        <button class="picker-skip" data-action="skip">I'll explore on my own</button>
      </div>
    `;
  }

  private bindPickerHandlers(): void {
    const popoverRoot = document.querySelector(".tutorial-picker-popover");
    if (!popoverRoot) return;

    popoverRoot.querySelectorAll<HTMLElement>("button[data-flow-id]").forEach(btn => {
      btn.addEventListener("click", () => {
        const flowId = btn.dataset["flowId"];
        if (!flowId || btn.hasAttribute("disabled")) return;
        this.tutorialService.markWelcomeSeen();
        this.welcomeDriver?.destroy();
        setTimeout(() => this.tutorialService.start(flowId), 250);
      });
    });

    const skipBtn = popoverRoot.querySelector<HTMLElement>("button[data-action='skip']");
    skipBtn?.addEventListener("click", () => {
      this.tutorialService.markWelcomeSeen();
      this.welcomeDriver?.destroy();
    });
  }

  private showResumePopover(): void {
    const p = this.progress ?? this.tutorialService.progress;
    if (!p.current) return;
    const flow = getFlowById(p.current.flowId);
    if (!flow) return;

    const stepNum = (p.current.stepIndex ?? 0) + 1;
    this.resumeDriver = driver({
      showProgress: false,
      allowClose: true,
      overlayColor: "#000",
      overlayOpacity: 0.65,
      stagePadding: 0,
      popoverClass: "tutorial-welcome-popover tutorial-resume-popover",
      showButtons: ["close"],
      onCloseClick: () => this.resumeDriver?.destroy(),
      onDestroyed: () => {
        this.resumeDriver = null;
      },
      steps: [
        {
          popover: {
            title: "👋 Welcome back!",
            description: `
              <div class="tutorial-resume">
                <p>You're partway through <b>${flow.name}</b> — step ${stepNum} of ${flow.steps.length}.</p>
                <p style="color:#666; margin: 8px 0 14px">Pick up where you left off, restart this flow, or browse other tours.</p>
                <div class="resume-actions">
                  <button class="resume-btn primary" data-action="resume">Resume ▶</button>
                  <button class="resume-btn" data-action="restart">Restart this flow</button>
                  <button class="resume-btn ghost" data-action="picker">Pick another tour</button>
                </div>
              </div>
            `,
          },
        },
      ],
    });
    this.resumeDriver.drive();

    setTimeout(() => this.bindResumeHandlers(flow), 50);
  }

  private bindResumeHandlers(flow: TutorialFlow): void {
    const root = document.querySelector(".tutorial-resume-popover");
    if (!root) return;
    root.querySelector<HTMLElement>("[data-action='resume']")?.addEventListener("click", () => {
      this.resumeDriver?.destroy();
      setTimeout(() => this.tutorialService.resume(), 250);
    });
    root.querySelector<HTMLElement>("[data-action='restart']")?.addEventListener("click", () => {
      this.resumeDriver?.destroy();
      setTimeout(() => this.tutorialService.start(flow.id), 250);
    });
    root.querySelector<HTMLElement>("[data-action='picker']")?.addEventListener("click", () => {
      this.resumeDriver?.destroy();
      setTimeout(() => this.showFlowPickerPopover(), 250);
    });
  }

  /**
   * Asks the user whether to chain into the next flow after the current one
   * finished. Driven by the completed flow's `chainTo` field — fires after a
   * short delay so the celebration burst + badge notification land first.
   */
  private showChainConfirmPopover(finished: TutorialFlow, next: TutorialFlow): void {
    this.chainDriver = driver({
      showProgress: false,
      allowClose: true,
      overlayColor: "#000",
      overlayOpacity: 0.65,
      stagePadding: 0,
      popoverClass: "tutorial-welcome-popover tutorial-resume-popover",
      showButtons: ["close"],
      onCloseClick: () => this.chainDriver?.destroy(),
      onDestroyed: () => {
        this.chainDriver = null;
      },
      steps: [
        {
          popover: {
            title: `${finished.badge.emoji} ${finished.badge.name} unlocked!`,
            description: `
              <div class="tutorial-resume">
                <p>Nice — you've got the lay of the land. Want to build your first workflow now?</p>
                <div class="resume-actions">
                  <button class="resume-btn primary" data-action="continue">Continue</button>
                  <button class="resume-btn ghost" data-action="later">Not now</button>
                </div>
              </div>
            `,
          },
        },
      ],
    });
    this.chainDriver.drive();

    setTimeout(() => this.bindChainHandlers(next), 50);
  }

  private bindChainHandlers(next: TutorialFlow): void {
    const root = document.querySelector(".tutorial-resume-popover");
    if (!root) return;
    root.querySelector<HTMLElement>("[data-action='continue']")?.addEventListener("click", () => {
      this.chainDriver?.destroy();
      setTimeout(() => this.tutorialService.start(next.id), 250);
    });
    root.querySelector<HTMLElement>("[data-action='later']")?.addEventListener("click", () => {
      this.chainDriver?.destroy();
    });
  }

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

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.welcomeDriver?.destroy();
    this.resumeDriver?.destroy();
    this.chainDriver?.destroy();
  }
}
