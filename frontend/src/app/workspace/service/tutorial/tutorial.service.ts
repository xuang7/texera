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

import { Injectable, NgZone, OnDestroy } from "@angular/core";
import { BehaviorSubject, Subject } from "rxjs";
import { filter, takeUntil } from "rxjs/operators";
import { driver, Driver, DriveStep } from "driver.js";
import { WorkflowActionService } from "../workflow-graph/model/workflow-action.service";
import { ExecuteWorkflowService } from "../execute-workflow/execute-workflow.service";
import { ExecutionState } from "../../types/execute-workflow.interface";
import { ComputingUnitStatusService } from "../../../common/service/computing-unit/computing-unit-status/computing-unit-status.service";
import { ComputingUnitState } from "../../../common/type/computing-unit-connection.interface";
import {
  AutoAdvanceTrigger,
  BONUS_BADGES,
  BadgeDef,
  FLOWS,
  GRADUATE_BADGE,
  SPEED_RUNNER_BADGE,
  TutorialFlow,
  TutorialStep,
  getFlowById,
} from "./flows";
import { DEFAULT_PROGRESS, TutorialProgress, loadProgress, saveProgress } from "./tutorial-progress";
const XP_PER_STEP = 10;
const SPEED_RUNNER_THRESHOLD_MS = 90_000;

export interface BadgeUnlockEvent {
  badge: BadgeDef;
  flowName: string;
}

export type { TutorialStep, TutorialFlow, BadgeDef, AutoAdvanceTrigger };

@Injectable({
  providedIn: "root",
})
export class TutorialService implements OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private driverObj: Driver | null = null;
  private currentFlow: TutorialFlow | null = null;
  private lastActiveIndex = 0;
  private flowStartedAt = 0;

  // One-shot DOM click listener for steps with `advanceOnClick: true`.
  private clickAdvanceEl: Element | null = null;
  private clickAdvanceHandler: ((ev: Event) => void) | null = null;

  // ===== Reactive state =====
  private readonly isActiveSubject = new BehaviorSubject<boolean>(false);
  public readonly isActive$ = this.isActiveSubject.asObservable();

  private readonly currentStepSubject = new BehaviorSubject<number>(0);
  public readonly currentStep$ = this.currentStepSubject.asObservable();

  private readonly currentFlowSubject = new BehaviorSubject<TutorialFlow | null>(null);
  public readonly currentFlow$ = this.currentFlowSubject.asObservable();

  private readonly completedSubject = new Subject<{ flowId: string }>();
  public readonly completed$ = this.completedSubject.asObservable();

  private readonly badgeUnlockedSubject = new Subject<BadgeUnlockEvent>();
  public readonly badgeUnlocked$ = this.badgeUnlockedSubject.asObservable();

  private readonly progressSubject = new BehaviorSubject<TutorialProgress>(DEFAULT_PROGRESS);
  public readonly progress$ = this.progressSubject.asObservable();

  public readonly flows: TutorialFlow[] = FLOWS;

  constructor(
    private workflowActionService: WorkflowActionService,
    private executeWorkflowService: ExecuteWorkflowService,
    private computingUnitStatusService: ComputingUnitStatusService,
    private ngZone: NgZone
  ) {
    this.progressSubject.next(loadProgress());
    this.wireEventListeners();
  }

  // ===== Getters =====

  get isActive(): boolean {
    return this.isActiveSubject.getValue();
  }

  get currentStepIndex(): number {
    return this.currentStepSubject.getValue();
  }

  get currentStep(): TutorialStep | null {
    if (!this.isActive || !this.currentFlow) return null;
    return this.currentFlow.steps[this.currentStepIndex] ?? null;
  }

  get currentFlowSnapshot(): TutorialFlow | null {
    return this.currentFlow;
  }

  get stepCount(): number {
    return this.currentFlow?.steps.length ?? 0;
  }

  get progress(): TutorialProgress {
    return this.progressSubject.getValue();
  }

  // ===== Flow control =====

  /** Start a flow. Re-runs of completed flows act as replays (no second badge, sparkles still fire). */
  start(flowId: string, startAt: number = 0): void {
    const flow = getFlowById(flowId);
    if (!flow) {
      // eslint-disable-next-line no-console
      console.warn(`[tutorial] unknown flow "${flowId}"`);
      return;
    }
    if (flow.comingSoon || flow.steps.length === 0) {
      return;
    }

    if (this.driverObj) {
      this.driverObj.destroy();
      this.driverObj = null;
    }
    this.resetOperatorMenuCategories();
    const safeStart = Math.max(0, Math.min(startAt, flow.steps.length - 1));
    this.currentFlow = flow;
    this.currentFlowSubject.next(flow);
    this.lastActiveIndex = safeStart;
    this.flowStartedAt = Date.now();
    this.currentStepSubject.next(safeStart);
    this.isActiveSubject.next(true);

    // Persist the start so a refresh mid-tour can offer to resume.
    this.updateProgress(p => ({ ...p, current: { flowId, stepIndex: safeStart } }));

    const driverSteps: DriveStep[] = flow.steps.map(step => {
      // skipToStep redirects the default Next button. driver.js exposes onNextClick on Popover, not DriveStep.
      const popover =
        typeof step.skipToStep === "number"
          ? { ...step.popover, onNextClick: () => this.driverObj?.moveTo(step.skipToStep!) }
          : step.popover;
      return {
        element: step.element,
        popover,
      };
    });

    // Skip prebuilt-workflow load on resume so we don't wipe the user's mid-tour canvas.
    if (flow.prebuiltWorkflow && safeStart === 0) {
      this.loadPrebuiltWorkflow(flow.prebuiltWorkflow)
        .catch(err => {
          // eslint-disable-next-line no-console
          console.warn(`[tutorial] failed to load prebuilt workflow "${flow.prebuiltWorkflow}":`, err);
        })
        .finally(() => this.launchDriver(driverSteps, safeStart));
      return;
    }

    this.launchDriver(driverSteps, safeStart);
  }

  /** Load a fixture from `assets/tutorial-workflows/`. Resolves after JointJS has had a tick to paint. */
  private loadPrebuiltWorkflow(name: string): Promise<void> {
    return fetch(`assets/tutorial-workflows/${name}.json`)
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(workflow => {
        this.ngZone.run(() => this.workflowActionService.reloadWorkflow(workflow));
        // Wait one paint tick for JointJS so spotlight rects measure correctly.
        return new Promise<void>(resolve => setTimeout(resolve, 150));
      });
  }

  private launchDriver(driverSteps: DriveStep[], safeStart: number): void {
    // Construct driver.js outside Angular's zone — its resize / scroll / mutation listeners would
    // otherwise re-run change detection on every event and make ng-zorro tooltips/waves jitter.
    this.ngZone.runOutsideAngular(() => {
      this.driverObj = driver({
        showProgress: true,
        allowClose: true,
        overlayClickBehavior: () => {
          /* no-op: clicking the dim overlay does nothing */
        },
        animate: false,
        smoothScroll: true,
        stagePadding: 16,
        stageRadius: 8,
        disableActiveInteraction: false,
        overlayColor: "#000",
        overlayOpacity: 0.65,
        nextBtnText: "Next →",
        prevBtnText: "← Back",
        doneBtnText: "All done! 🎉",
        progressText: "Step {{current}} / {{total}}",
        steps: driverSteps,
        onHighlightStarted: (el, _step, opts) => this.handleHighlight(el, opts),
        onDestroyed: () => this.handleDestroyed(),
      });
      this.driverObj.drive(safeStart);
    });
  }

  /** Continue an in-progress flow (resume from saved stepIndex). */
  resume(): boolean {
    const cur = this.progress.current;
    if (!cur) return false;
    const flow = getFlowById(cur.flowId);
    if (!flow || flow.comingSoon || flow.steps.length === 0) return false;
    this.start(cur.flowId, cur.stepIndex);
    return true;
  }

  next(): void {
    this.driverObj?.moveNext();
  }

  previous(): void {
    this.driverObj?.movePrevious();
  }

  skip(): void {
    this.driverObj?.destroy();
  }

  restart(): void {
    if (this.currentFlow) this.start(this.currentFlow.id);
  }

  markWelcomeSeen(): void {
    this.updateProgress(p => ({ ...p, seenWelcome: true }));
  }

  /** Wipe all progress — useful for demos / workshops. */
  resetProgress(): void {
    this.progressSubject.next({ ...DEFAULT_PROGRESS });
    saveProgress(this.progressSubject.getValue());
  }

  // ===== Private helpers =====

  /** Collapse every expanded operator-menu category so "Click Data Input to open the category" makes sense regardless of prior state. */
  private resetOperatorMenuCategories(): void {
    const menu = document.querySelector("texera-operator-menu");
    if (!menu) return;
    menu.querySelectorAll(".ant-collapse-item-active > .ant-collapse-header").forEach(header => {
      (header as HTMLElement).click();
    });
  }

  /** Inject a "Learn more" button next to Next on group-overview steps. Idempotent. */
  private injectShowDetailsButton(stepIdx: number): void {
    const popover = document.querySelector(".driver-popover");
    if (!popover || popover.querySelector(".tutorial-show-details-btn")) return;

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "tutorial-show-details-btn";
    btn.textContent = "Learn more";
    btn.addEventListener("click", () => {
      if (this.currentStepIndex !== stepIdx) return;
      this.driverObj?.moveNext();
    });

    // Prefer sitting in the footer alongside Next/Skip; fall back to the
    // description block if driver.js's footer markup changes in the future.
    const navBtns = popover.querySelector(".driver-popover-navigation-btns");
    if (navBtns) {
      navBtns.insertBefore(btn, navBtns.firstChild);
      return;
    }
    const description = popover.querySelector(".driver-popover-description");
    description?.appendChild(btn);
  }

  /** True if the box is inside the viewport AND every ancestor scroll container's clip rect. */
  private isFullyVisible(el: HTMLElement): boolean {
    const r = el.getBoundingClientRect();
    if (r.top < 0 || r.left < 0 || r.bottom > window.innerHeight || r.right > window.innerWidth) {
      return false;
    }
    let p: HTMLElement | null = el.parentElement;
    while (p && p !== document.body) {
      const style = getComputedStyle(p);
      const scrollsY = /auto|scroll|hidden|clip/.test(style.overflowY);
      const scrollsX = /auto|scroll|hidden|clip/.test(style.overflowX);
      if (scrollsY || scrollsX) {
        const pr = p.getBoundingClientRect();
        if (
          (scrollsY && (r.top < pr.top || r.bottom > pr.bottom)) ||
          (scrollsX && (r.left < pr.left || r.right > pr.right))
        ) {
          return false;
        }
      }
      p = p.parentElement;
    }
    return true;
  }

  private updateProgress(updater: (p: TutorialProgress) => TutorialProgress): void {
    const next = updater(this.progressSubject.getValue());
    this.progressSubject.next(next);
    saveProgress(next);
  }

  private handleHighlight(el: Element | undefined, opts: { state?: { activeIndex?: number } } | undefined): void {
    const idx = opts?.state?.activeIndex ?? 0;
    this.lastActiveIndex = idx;
    this.ngZone.run(() => this.currentStepSubject.next(idx));
    this.detachClickAdvance();
    const step = this.currentFlow?.steps[idx];
    if (!step || !this.currentFlow) return;

    // Collapse expanded operator-menu categories before highlighting a TOP-LEVEL category, so prior
    // expansions don't push the target down. Skip for nested sub-groups (the parent must stay open,
    // otherwise the sub-group rect goes 0×0 and auto-skip jumps past the step). Refresh after the
    // ~300ms collapse animation since driver.js measures synchronously.
    if (typeof step.element === "string" && /nz-collapse-panel\[data-group-name=/.test(step.element)) {
      const target = document.querySelector(step.element) as HTMLElement | null;
      const isTopLevel = target?.getAttribute("data-depth") === "0";
      if (isTopLevel) {
        this.resetOperatorMenuCategories();
        setTimeout(() => this.driverObj?.refresh(), 350);
      }
    }

    // Persist the new position so refresh mid-tour resumes here.
    this.updateProgress(p => ({
      ...p,
      current: { flowId: this.currentFlow!.id, stepIndex: idx },
    }));

    if (step.showDetailsButton) {
      setTimeout(() => this.injectShowDetailsButton(idx), 0);
    }

    // Scroll only when not already fully visible — keeps fixed toolbar icons flicker-free.
    if (el && typeof (el as HTMLElement).scrollIntoView === "function") {
      if (!this.isFullyVisible(el as HTMLElement)) {
        (el as HTMLElement).scrollIntoView({ block: "center", inline: "nearest" });
        setTimeout(() => this.driverObj?.refresh(), 60);
      }
    }

    // Auto-skip steps whose target is missing / zero-size. Skip the final step (moveNext ends the tour).
    const isLastStep = idx === this.currentFlow.steps.length - 1;
    if (typeof step.element === "string" && !isLastStep) {
      const selector = step.element;
      setTimeout(() => {
        if (this.currentStepIndex !== idx || !this.driverObj) return;
        const reEl = document.querySelector(selector) as HTMLElement | null;
        const rect = reEl?.getBoundingClientRect();
        const missing = !reEl || !rect || (rect.width === 0 && rect.height === 0);
        if (missing) {
          this.ngZone.run(() => this.driverObj?.moveNext());
        }
      }, 250);
    }

    // Fire a micro-reward (sparkles + XP) — but only the first time the user
    // hits this step. Replays don't keep stacking XP.
    this.fireMicroRewardIfFirstTime(idx);

    if (!el && step.element) {
      // eslint-disable-next-line no-console
      console.warn(`[tutorial] step ${idx + 1} highlight: NO ELEMENT MATCHED for`, step.element);
    }
    if (step.advanceOnClick && el) this.attachClickAdvance(el);
  }

  private handleDestroyed(): void {
    this.detachClickAdvance();
    this.ngZone.run(() => {
      const flow = this.currentFlow;
      const wasOnLast = flow ? this.lastActiveIndex === flow.steps.length - 1 : false;
      this.driverObj = null;
      this.isActiveSubject.next(false);
      this.currentFlowSubject.next(null);
      if (wasOnLast && flow) this.finalizeFlowCompletion(flow);
      else {
        // User skipped mid-flow — keep `current` so the resume toast can fire later.
      }
      this.currentFlow = null;
    });
  }

  private finalizeFlowCompletion(flow: TutorialFlow): void {
    const durationMs = Date.now() - this.flowStartedAt;
    const isFirstCompletion = !this.progress.completed[flow.id];

    this.updateProgress(p => {
      const completed = { ...p.completed, [flow.id]: { completedAt: Date.now(), durationMs } };
      const earned = new Set(p.earnedBadges);
      const newlyEarned: BadgeDef[] = [];

      if (isFirstCompletion && !earned.has(flow.badge.id)) {
        earned.add(flow.badge.id);
        newlyEarned.push(flow.badge);
      }
      // Speed runner bonus
      if (durationMs < SPEED_RUNNER_THRESHOLD_MS && !earned.has(SPEED_RUNNER_BADGE.id)) {
        earned.add(SPEED_RUNNER_BADGE.id);
        newlyEarned.push(SPEED_RUNNER_BADGE);
      }
      // Graduate bonus — only when every primary flow badge is earned.
      const flowBadgeIds = FLOWS.map(f => f.badge.id);
      const hasAllFlowBadges = flowBadgeIds.every(id => earned.has(id));
      if (hasAllFlowBadges && !earned.has(GRADUATE_BADGE.id)) {
        earned.add(GRADUATE_BADGE.id);
        newlyEarned.push(GRADUATE_BADGE);
      }

      // Fire badge-unlocked notifications after the state has been written.
      setTimeout(() => {
        newlyEarned.forEach(badge => this.badgeUnlockedSubject.next({ badge, flowName: flow.name }));
      }, 0);

      return {
        ...p,
        completed,
        earnedBadges: Array.from(earned),
        current: undefined,
      };
    });

    this.completedSubject.next({ flowId: flow.id });
  }

  private fireMicroRewardIfFirstTime(stepIdx: number): void {
    if (!this.currentFlow) return;
    const flowId = this.currentFlow.id;
    const microsForFlow = this.progress.microsEarned[flowId] ?? [];
    if (microsForFlow.includes(stepIdx)) return; // already rewarded

    this.updateProgress(p => ({
      ...p,
      xp: p.xp + XP_PER_STEP,
      microsEarned: {
        ...p.microsEarned,
        [flowId]: [...(p.microsEarned[flowId] ?? []), stepIdx],
      },
    }));
    this.spawnSparkleBurst();
  }

  /** Pure-DOM sparkle + XP burst, anchored top-right so it never overlaps the spotlight. */
  private spawnSparkleBurst(): void {
    const burst = document.createElement("div");
    burst.className = "tutorial-sparkle-burst";

    // Bias particle trajectories to fly DOWN-LEFT (into the workspace),
    // not up-right (off-screen) since the burst sits in the top-right corner.
    const angles = [110, 140, 170, 200, 230, 260];
    angles.forEach((angle, i) => {
      const radius = 18 + Math.random() * 8;
      const dx = Math.cos((angle * Math.PI) / 180) * radius;
      const dy = Math.sin((angle * Math.PI) / 180) * radius;
      const sp = document.createElement("span");
      sp.className = "sparkle-particle";
      sp.textContent = i % 2 === 0 ? "✦" : "✧";
      sp.style.setProperty("--dx", `${dx}px`);
      sp.style.setProperty("--dy", `${dy}px`);
      sp.style.animationDelay = `${i * 30}ms`;
      burst.appendChild(sp);
    });

    const xp = document.createElement("div");
    xp.className = "xp-float";
    xp.textContent = `+${XP_PER_STEP} XP`;
    burst.appendChild(xp);

    document.body.appendChild(burst);
    setTimeout(() => burst.remove(), 1300);
  }

  private attachClickAdvance(el: Element): void {
    this.clickAdvanceEl = el;
    this.clickAdvanceHandler = () => {
      setTimeout(() => this.ngZone.run(() => this.driverObj?.moveNext()), 500);
    };
    el.addEventListener("click", this.clickAdvanceHandler, { once: true });
  }

  private detachClickAdvance(): void {
    if (this.clickAdvanceEl && this.clickAdvanceHandler) {
      this.clickAdvanceEl.removeEventListener("click", this.clickAdvanceHandler);
    }
    this.clickAdvanceEl = null;
    this.clickAdvanceHandler = null;
  }

  private autoAdvanceIfMatches(trigger: AutoAdvanceTrigger): void {
    if (!this.isActive || !this.driverObj || !this.currentFlow) return;
    const step = this.currentFlow.steps[this.currentStepIndex];
    if (step?.autoAdvanceOn !== trigger) return;
    // When a drag-to-canvas finishes, fold the operator menu category back
    // immediately — waiting until the next category step is too late: the
    // category visually stays open during the entire intermediate steps
    // (click operator, open property panel, configure, ...).
    if (trigger === "operatorAdded") {
      this.resetOperatorMenuCategories();
    }
    setTimeout(() => this.ngZone.run(() => this.driverObj?.moveNext()), 700);
  }

  private wireEventListeners(): void {
    this.workflowActionService
      .getTexeraGraph()
      .getOperatorAddStream()
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.autoAdvanceIfMatches("operatorAdded"));

    this.workflowActionService
      .getTexeraGraph()
      .getLinkAddStream()
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.autoAdvanceIfMatches("linkAdded"));

    this.workflowActionService
      .getTexeraGraph()
      .getOperatorPropertyChangeStream()
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.autoAdvanceIfMatches("propertyChanged"));

    this.workflowActionService
      .getJointGraphWrapper()
      .getJointOperatorHighlightStream()
      .pipe(
        filter(ids => ids.length > 0),
        takeUntil(this.destroy$)
      )
      .subscribe(() => this.autoAdvanceIfMatches("operatorSelected"));

    this.executeWorkflowService
      .getExecutionStateStream()
      .pipe(
        filter(({ current }) => current.state === ExecutionState.Running || current.state === ExecutionState.Completed),
        takeUntil(this.destroy$)
      )
      .subscribe(() => this.autoAdvanceIfMatches("executionStarted"));

    this.computingUnitStatusService
      .getStatus()
      .pipe(
        filter(state => state === ComputingUnitState.Running),
        takeUntil(this.destroy$)
      )
      .subscribe(() => this.autoAdvanceIfMatches("computingUnitConnected"));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.detachClickAdvance();
    this.driverObj?.destroy();
  }
}
