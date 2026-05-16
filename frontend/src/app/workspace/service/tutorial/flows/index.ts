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

import { DriveStep } from "driver.js";
import { OVERVIEW_WORKSPACE_FLOW } from "./overview-workspace";
import { BUILD_SIMPLE_FLOW } from "./build-simple";
import { BUILD_COMPLEX_FLOW } from "./build-complex";
import { PUBLIC_WORKFLOW_FLOW } from "./public-workflow";

export type AutoAdvanceTrigger =
  | "operatorAdded"
  | "operatorSelected"
  | "linkAdded"
  | "executionStarted"
  | "propertyChanged"
  | "computingUnitConnected";

export interface TutorialStep extends DriveStep {
  /** Short label used by the AI chat for context. */
  title: string;
  /** Prompt fragment given to the AI when the user asks a question on this step. */
  aiHint: string;
  /** If set, advance automatically when this event fires on the workflow. */
  autoAdvanceOn?: AutoAdvanceTrigger;
  /** If true, clicking the highlighted element advances to the next step. */
  advanceOnClick?: boolean;
  /** Default Next jumps to this index instead of `+1`. Pair with `showDetailsButton` for opt-in deep dives. */
  skipToStep?: number;
  /** Adds a secondary "Show details" button to the popover that does normal moveNext. Only meaningful with `skipToStep`. */
  showDetailsButton?: boolean;
}

export interface BadgeDef {
  id: string;
  emoji: string;
  name: string;
  description: string;
  /** Optional palette to tint the locked silhouette / unlocked frame. */
  hue?: string;
}

export interface TutorialFlow {
  id: string;
  name: string;
  shortDesc: string;
  difficulty: "intro" | "easy" | "medium" | "advanced";
  estimatedMinutes: number;
  /** Stub flows have empty `steps` arrays and aren't launchable yet. */
  comingSoon?: boolean;
  /** Flow ids the user is encouraged to finish first. */
  prerequisites?: string[];
  badge: BadgeDef;
  steps: TutorialStep[];
  /** Basename of a JSON fixture under `assets/tutorial-workflows/` to load into the canvas before step 0. */
  prebuiltWorkflow?: string;
  /**
   * Flow id to chain into automatically when this one completes.
   */
  chainTo?: string;
}

/** Coming-soon placeholders advertised in the picker but not launchable. */
const BUILD_ML_FLOW: TutorialFlow = {
  id: "build-ml",
  name: "Add a simple ML model",
  shortDesc: "Train a Logistic Regression classifier on a pre-built workflow.",
  difficulty: "advanced",
  estimatedMinutes: 2,
  prerequisites: ["build-simple"],
  comingSoon: true,
  badge: {
    id: "data-scientist",
    emoji: "🧪",
    name: "Data Scientist",
    description: "Trained your first ML model inside Texera.",
    hue: "#52c41a",
  },
  steps: [],
};

const HUB_TOUR_FLOW: TutorialFlow = {
  id: "hub-tour",
  name: "Tour the Texera hub",
  shortDesc: "Workflows, datasets, public projects — start here.",
  difficulty: "intro",
  estimatedMinutes: 1,
  comingSoon: true,
  badge: {
    id: "first-steps",
    emoji: "🚀",
    name: "First Steps",
    description: "Took the Texera hub orientation tour.",
    hue: "#1890ff",
  },
  steps: [],
};

export const FLOWS: TutorialFlow[] = [
  OVERVIEW_WORKSPACE_FLOW,
  BUILD_SIMPLE_FLOW,
  BUILD_COMPLEX_FLOW,
  BUILD_ML_FLOW,
  HUB_TOUR_FLOW,
  PUBLIC_WORKFLOW_FLOW,
];

export function getFlowById(id: string): TutorialFlow | null {
  return FLOWS.find(f => f.id === id) ?? null;
}

/** Special meta-badge granted automatically when every other badge is earned. */
export const GRADUATE_BADGE: BadgeDef = {
  id: "graduate",
  emoji: "🎓",
  name: "Texera Graduate",
  description: "Completed every tutorial flow — true Texera fluency.",
  hue: "#722ed1",
};

/** Bonus badge for fast completions. */
export const SPEED_RUNNER_BADGE: BadgeDef = {
  id: "speed-runner",
  emoji: "⚡",
  name: "Speed Runner",
  description: "Finished a flow in under 90 seconds.",
  hue: "#fa8c16",
};

export const BONUS_BADGES: BadgeDef[] = [SPEED_RUNNER_BADGE, GRADUATE_BADGE];
