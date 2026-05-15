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

type AutoAdvanceTrigger =
  | "operatorAdded"
  | "operatorSelected"
  | "linkAdded"
  | "executionStarted"
  | "propertyChanged"
  | "computingUnitConnected";

export interface TutorialStep extends DriveStep {
  /** Short label used by the AI chat for context */
  title: string;
  /** Prompt fragment given to the AI when the user asks a question on this step */
  aiHint: string;
  /** If set, advance automatically when this event fires on the workflow */
  autoAdvanceOn?: AutoAdvanceTrigger;
  /** If true, clicking the highlighted element advances to the next step */
  advanceOnClick?: boolean;
}

export const TUTORIAL_STEPS: TutorialStep[] = [
  {
    element: "texera-operator-menu",
    popover: {
      title: "👋 Welcome to your toolbox",
      description:
        "This panel on the <b>LEFT</b> is your toolbox. Each tile here is an <b>operator</b> — a tiny building block that does <i>one</i> thing to your data. We're going to wire two of them together!",
      side: "right",
      align: "start",
    },
    title: "Step 1 — The Operator Panel",
    aiHint: "The user just opened Texera and is being introduced to the operator panel on the left. Intro only — user clicks Next to advance.",
  },
  {
    element: 'nz-collapse-panel[data-group-name="Data Input"]',
    popover: {
      title: "First, let's get some data 📥",
      description:
        'Click <b>"Data Input"</b> to open the category. <b>Sources</b> are operators that pull data <i>into</i> your workflow — CSV files, databases, APIs.',
      side: "right",
      align: "start",
    },
    title: "Step 2 — Find a Source",
    aiHint: "The user is locating the Data Input category to find a source operator.",
    advanceOnClick: true,
  },
  {
    element: '.operator-label[data-operator-type="CSVFileScan"]',
    popover: {
      title: "Drag it onto the canvas 🎯",
      description:
        "Grab <b>CSV File Scan</b> and drop it onto the empty canvas on the right. This little box's job: read rows from a CSV file.",
      side: "right",
      align: "start",
    },
    title: "Step 3 — Drag CSV File Scan",
    aiHint: "The user is dragging the CSV File Scan source operator from the operator panel onto the canvas.",
    autoAdvanceOn: "operatorAdded",
  },
  {
    element: '[model-id^="CSVFileScan-operator-"]',
    popover: {
      title: "Nice! Now give it a click",
      description:
        "There's your <b>CSV File Scan</b> on the canvas. <b>Click it once</b> to select it — that's how we tell Texera \"I want to configure this one\".",
      side: "bottom",
      align: "center",
    },
    title: "Step 4 — Click the Operator",
    aiHint: "The user needs to click the just-placed CSV File Scan operator on the canvas to open its property editor.",
    autoAdvanceOn: "operatorSelected",
  },
  {
    element: 'li[data-tutorial="open-property-panel"]',
    popover: {
      title: "Open its settings ⚙️",
      description:
        "See the little <b>form icon</b> at the top-right? <b>Click it</b> to slide open the settings panel on the right side. <i>(Already open? We'll just skip ahead.)</i>",
      side: "left",
      align: "center",
    },
    title: "Step 5 — Open the Property Panel",
    aiHint: "The user needs to click the small form icon at the top-right of the canvas to expand the (currently collapsed) property panel. Auto-skipped when the panel is already open.",
    advanceOnClick: true,
  },
  {
    element: 'button[data-tutorial="file-select-button"]',
    popover: {
      title: "Pick a file 📂",
      description: "Click <b>Select File</b> to open the dataset browser — that's where your CSVs live.",
      side: "left",
      align: "center",
    },
    title: "Step 6 — Open the File Picker",
    aiHint: "The user is about to click the Select File button to open the dataset selection modal.",
    advanceOnClick: true,
  },
  {
    element: ".ant-modal-content",
    popover: {
      title: "Browse your data",
      description:
        "Pick a <b>dataset</b> on the left, expand a version, then <b>double-click any .csv</b>. The modal closes itself and the file name auto-fills — no typing required ✨.",
      side: "top",
      align: "end",
    },
    title: "Step 7 — Pick a CSV File",
    aiHint: "The user is choosing a CSV file from the dataset selection modal. After they pick one, the modal closes and the fileName property updates.",
    autoAdvanceOn: "propertyChanged",
  },
  {
    element: 'nz-collapse-panel[data-group-name="Data Cleaning"]',
    popover: {
      title: "Time for a second operator 🧰",
      description:
        'Back to the <b>left panel</b>. Click <b>"Data Cleaning"</b> to open that category — we\'re about to chain a second operator after our source.',
      side: "right",
      align: "start",
    },
    title: "Step 8 — Find the Data Cleaning Category",
    aiHint: "The user needs to expand the Data Cleaning category in the operator panel to find Limit.",
    advanceOnClick: true,
  },
  {
    element: '.operator-label[data-operator-type="Limit"]',
    popover: {
      title: "Drag Limit onto the canvas",
      description:
        "Grab <b>Limit</b> and drop it on the canvas next to CSV File Scan. Limit caps your output to the first N rows — perfect for a quick peek.",
      side: "right",
      align: "start",
    },
    title: "Step 9 — Drag Limit",
    aiHint: "The user is dragging the Limit operator onto the canvas as a second operator.",
    autoAdvanceOn: "operatorAdded",
  },
  {
    element: "texera-workflow-editor",
    popover: {
      title: "Wire them up 🔗",
      description:
        "Drag from the <b>▶ port on the right edge of CSV File Scan</b> over to the <b>port on the left edge of Limit</b>. That edge tells Texera \"send rows this way\".",
      side: "over",
      align: "center",
    },
    title: "Step 10 — Connect Operators",
    aiHint: "The user is dragging an edge from CSV File Scan's output port to Limit's input port.",
    autoAdvanceOn: "linkAdded",
  },
  {
    element: '[model-id^="Limit-operator-"]',
    popover: {
      title: "Click Limit to configure it",
      description:
        "Click the <b>Limit</b> box on the canvas. The property panel on the right will swap over to <i>its</i> settings — each operator has its own.",
      side: "bottom",
      align: "center",
    },
    title: "Step 11 — Click Limit",
    aiHint: "The user needs to click the just-connected Limit operator on the canvas to switch the property editor to its fields.",
    autoAdvanceOn: "operatorSelected",
  },
  {
    element: 'li[data-tutorial="open-property-panel"]',
    popover: {
      title: "Open its settings",
      description:
        "If the panel collapsed again, click the <b>form icon</b> at the top-right to reopen it. <i>(Already open? Skipping…)</i>",
      side: "left",
      align: "center",
    },
    title: "Step 12 — Open the Property Panel (Limit)",
    aiHint: "Same as step 5: the user clicks the form icon to reopen the property panel for the Limit operator. Auto-skipped when the panel is already open.",
    advanceOnClick: true,
  },
  {
    element: ".property-editor-form",
    popover: {
      title: "How many rows? 🔢",
      description:
        "Type a number into the <b>Limit</b> field — that's the max rows you'll see. <b>Try 10</b> for a snappy preview.",
      side: "left",
      align: "start",
    },
    title: "Step 13 — Configure Limit",
    aiHint: "The user is configuring the Limit operator by typing a number in the limit field (max output rows).",
    autoAdvanceOn: "propertyChanged",
  },
  {
    element: "#texera-compute-unit-selection",
    popover: {
      title: "Almost there — pick a Computing Unit ⚡",
      description:
        "Before running, click the <b>Computing Unit</b> selector at the top and pick (or start) one. That's the little worker that'll execute your workflow.",
      side: "bottom",
      align: "start",
    },
    title: "Step 14 — Connect a Computing Unit",
    aiHint: "The user needs to select/connect a Computing Unit from the top menu before running the workflow.",
    autoAdvanceOn: "computingUnitConnected",
  },
  {
    element: "#run-button",
    popover: {
      title: "Hit Run! ▶️",
      description: "Once the unit shows <b>Connected</b>, smash that <b>▶ Run</b> button and watch Texera get to work.",
      side: "bottom",
      align: "start",
    },
    title: "Step 15 — Run the Workflow",
    aiHint: "The user is clicking the Run button at the top to execute the workflow.",
    advanceOnClick: true,
  },
  {
    element: 'li[data-tutorial="open-result-panel"]',
    popover: {
      title: "Open the result panel 📊",
      description:
        "Your workflow finished — the data is ready! <b>Click this little square icon</b> at the bottom-left to slide open the result panel and see your rows.",
      side: "top",
      align: "start",
    },
    title: "Step 16 — Open the Result Panel",
    aiHint: "The user just ran the workflow. They need to click the 'Open Result Panel' icon (the square / border icon at the bottom-left of the screen) to expand the result panel and view their data.",
    advanceOnClick: true,
  },
  {
    popover: {
      title: "🎉 You did it!",
      description:
        "Your CSV rows are now waiting for you in the result panel. You just built and ran your very first Texera workflow — from empty canvas to real data in under two minutes. <b>Welcome aboard!</b> ✨",
    },
    title: "Step 17 — Celebration",
    aiHint: "Final celebratory step. The user has opened the result panel and can now see their workflow's output. No element is highlighted — the popover is centered like the welcome screen for a clean closing moment.",
  },
];

@Injectable({
  providedIn: "root",
})
export class TutorialService implements OnDestroy {
  private readonly destroy$ = new Subject<void>();
  private driverObj: Driver | null = null;
  private lastActiveIndex = 0;

  // One-shot DOM click listener for steps with `advanceOnClick: true`.
  private clickAdvanceEl: Element | null = null;
  private clickAdvanceHandler: ((ev: Event) => void) | null = null;

  private readonly isActiveSubject = new BehaviorSubject<boolean>(false);
  public readonly isActive$ = this.isActiveSubject.asObservable();

  private readonly currentStepSubject = new BehaviorSubject<number>(0);
  public readonly currentStep$ = this.currentStepSubject.asObservable();

  private readonly completedSubject = new Subject<void>();
  public readonly completed$ = this.completedSubject.asObservable();

  public readonly steps: TutorialStep[] = TUTORIAL_STEPS;

  constructor(
    private workflowActionService: WorkflowActionService,
    private executeWorkflowService: ExecuteWorkflowService,
    private computingUnitStatusService: ComputingUnitStatusService,
    private ngZone: NgZone
  ) {
    this.wireEventListeners();
  }

  get isActive(): boolean {
    return this.isActiveSubject.getValue();
  }

  get currentStepIndex(): number {
    return this.currentStepSubject.getValue();
  }

  get currentStep(): TutorialStep | null {
    return this.isActive ? (TUTORIAL_STEPS[this.currentStepIndex] ?? null) : null;
  }

  get stepCount(): number {
    return TUTORIAL_STEPS.length;
  }

  start(): void {
    if (this.driverObj) {
      this.driverObj.destroy();
      this.driverObj = null;
    }
    this.lastActiveIndex = 0;
    this.currentStepSubject.next(0);
    this.isActiveSubject.next(true);

    const driverSteps: DriveStep[] = TUTORIAL_STEPS.map(step => ({
      element: step.element,
      popover: step.popover,
    }));

    // Log selector resolution so missing targets are obvious in the console.
    // eslint-disable-next-line no-console
    console.info(
      "[tutorial] starting; selector-resolution report:",
      driverSteps.map((s, i) => ({
        idx: i,
        element: s.element,
        found: typeof s.element === "string" ? !!document.querySelector(s.element) : !!s.element,
      }))
    );

    this.driverObj = driver({
      showProgress: true,
      // Keep the top-right X button so the user always has an explicit exit,
      // but disarm overlay clicks so accidental clicks on the dim area don't
      // abort the tour.
      allowClose: true,
      overlayClickBehavior: () => {
        /* no-op: clicking the dim overlay does nothing */
      },
      animate: true,
      smoothScroll: true,
      // Generous padding so small spotlights (operator boxes on canvas,
      // small buttons in panels) have visual breathing room and stay easy
      // to click without nicking the dim overlay edge.
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
      onHighlightStarted: (el, _step, opts) => {
        const idx = opts?.state?.activeIndex ?? 0;
        this.lastActiveIndex = idx;
        this.ngZone.run(() => this.currentStepSubject.next(idx));
        this.detachClickAdvance();
        const step = TUTORIAL_STEPS[idx];

        // Force the spotlight target into view. driver.js's `smoothScroll`
        // sometimes misses nested scroll containers (e.g. the operator menu's
        // inner list when a category is scrolled below the fold). After the
        // browser settles the scroll, ask driver.js to re-measure with
        // `refresh()` so the spotlight + popover land at the new position.
        if (el && typeof (el as HTMLElement).scrollIntoView === "function") {
          (el as HTMLElement).scrollIntoView({ block: "center", inline: "nearest" });
          setTimeout(() => this.driverObj?.refresh(), 60);
        }

        // Auto-skip a step whose target doesn't exist (e.g. step 12's "open
        // the property panel" form icon, which only renders when width=0).
        // Re-query after a short delay to give Angular a tick to render, then
        // moveNext if the element is still missing or zero-sized. Never skip
        // the final step — moveNext() on it ends the tour, which makes the
        // celebratory "you're done" popover flash away instantly.
        const isLastStep = idx === TUTORIAL_STEPS.length - 1;
        if (typeof step?.element === "string" && !isLastStep) {
          const selector = step.element;
          setTimeout(() => {
            if (this.currentStepIndex !== idx || !this.driverObj) return;
            const reEl = document.querySelector(selector) as HTMLElement | null;
            const rect = reEl?.getBoundingClientRect();
            const missing = !reEl || !rect || (rect.width === 0 && rect.height === 0);
            if (missing) {
              // eslint-disable-next-line no-console
              console.info(
                `[tutorial] step ${idx + 1} target "${selector}" not found / zero-size; auto-skipping`
              );
              this.ngZone.run(() => this.driverObj?.moveNext());
            }
          }, 250);
        }

        // Debug: log the bounding rect of the spotlight target so
        // mis-positioned popovers can be diagnosed at a glance.
        if (el) {
          const rect = (el as HTMLElement).getBoundingClientRect?.();
          // eslint-disable-next-line no-console
          console.info(
            `[tutorial] step ${idx + 1} highlight:`,
            step?.title,
            "→",
            step?.element,
            rect && {
              x: Math.round(rect.x),
              y: Math.round(rect.y),
              w: Math.round(rect.width),
              h: Math.round(rect.height),
            }
          );
        } else if (step?.element) {
          // Only warn when an element was configured but couldn't be found.
          // Steps without `element` are intentionally centered popovers
          // (e.g. the final celebration) — that's not a failure mode.
          // eslint-disable-next-line no-console
          console.warn(`[tutorial] step ${idx + 1} highlight: NO ELEMENT MATCHED for`, step?.element);
        }
        if (step?.advanceOnClick && el) this.attachClickAdvance(el);
      },
      onDestroyed: () => {
        this.detachClickAdvance();
        this.ngZone.run(() => {
          const wasOnLast = this.lastActiveIndex === TUTORIAL_STEPS.length - 1;
          this.driverObj = null;
          this.isActiveSubject.next(false);
          if (wasOnLast) this.completedSubject.next();
        });
      },
    });
    this.driverObj.drive();
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
    this.start();
  }

  private autoAdvanceIfMatches(trigger: AutoAdvanceTrigger): void {
    if (!this.isActive || !this.driverObj) return;
    const step = TUTORIAL_STEPS[this.currentStepIndex];
    if (step?.autoAdvanceOn !== trigger) return;
    setTimeout(() => this.ngZone.run(() => this.driverObj?.moveNext()), 700);
  }

  private attachClickAdvance(el: Element): void {
    this.clickAdvanceEl = el;
    this.clickAdvanceHandler = () => {
      // Let the click's side-effects finish — collapse panel expand, ng-zorro
      // modal mount animation, etc. — before driver.js measures the next
      // target's bounding rect. 500ms covers ant-modal's ~300ms fade-in.
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
