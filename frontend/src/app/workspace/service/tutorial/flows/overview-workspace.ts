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

import type { TutorialFlow, TutorialStep } from "./index";

/**
 * Workspace orientation tour with branching detail dives.
 *
 * The main spine is 8 steps (Operator panel → 3 toolbar group overviews →
 * Result panel → Mini-map → AI Agent → Hand-off). Each group overview
 * offers two ways forward via the popover footer:
 *   - Next  → skips ahead to the next main step
 *   - Learn more → drills into the group's individual icons one by one
 *
 * The 14 detail steps hide their own progress counter (`showProgress: false`)
 * so the visible "Step X / 8" only ever reflects spine progress — detail
 * dives feel like an aside instead of inflating the perceived length.
 */
const STEPS: TutorialStep[] = [
  // 0 · MAIN 1/8
  {
    element: "texera-operator-menu",
    popover: {
      title: "Operator panel",
      description: "The building blocks of every workflow live here. Drag them onto the canvas to use them.",
      side: "right",
      align: "start",
      progressText: "Step 1 / 8",
    },
    title: "Step 1 — Operator Panel",
    aiHint: "Orientation: operator menu on the left.",
  },
  // 1 · MAIN 2/8 (branch: Next skips to idx 9, Learn more drills in)
  {
    element: "#user-buttons",
    popover: {
      title: "Toolbar · file actions",
      description: "Save, import, export, and other workflow-file operations.",
      side: "bottom",
      align: "start",
      progressText: "Step 2 / 8",
    },
    title: "Step 2 — Left group overview",
    aiHint:
      "Left toolbar group overview. Default Next skips past the 7 file-action details (idx 2-8) to the middle overview; 'Learn more' drills in.",
    skipToStep: 9,
    showDetailsButton: true,
  },
  // 2 · DETAIL
  {
    element: 'button[title="dashboard"]',
    popover: {
      title: "Dashboard",
      description: "Jump back to your workflow list.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Dashboard",
    aiHint: "Detail (left group): dashboard button.",
  },
  // 3 · DETAIL
  {
    element: 'button[title="create new"]',
    popover: {
      title: "New workflow",
      description: "Start a blank workflow in a new tab.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — New",
    aiHint: "Detail (left group): create-new button.",
  },
  // 4 · DETAIL
  {
    element: 'button[title="save"]',
    popover: {
      title: "Save",
      description: "Snapshots the workflow now instead of waiting for the next auto-save.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Save",
    aiHint: "Detail (left group): save button.",
  },
  // 5 · DETAIL
  {
    element: 'button[title="delete all"]',
    popover: {
      title: "Delete all",
      description: "Wipe every operator from the canvas — handy when you want a fresh start.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Delete all",
    aiHint: "Detail (left group): delete-all-operators button.",
  },
  // 6 · DETAIL
  {
    element: 'button[title="import workflow"]',
    popover: {
      title: "Import",
      description: "Upload a workflow JSON file and load it onto this canvas.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Import",
    aiHint: "Detail (left group): import-workflow button.",
  },
  // 7 · DETAIL
  {
    element: 'button[title="export workflow"]',
    popover: {
      title: "Export",
      description: "Download the current workflow as a JSON file.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Export",
    aiHint: "Detail (left group): export-workflow button.",
  },
  // 8 · DETAIL
  {
    element: 'button[title="change description"]',
    popover: {
      title: "Description",
      description: "Edit the workflow's description text.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Description",
    aiHint: "Detail (left group): change-description button.",
  },
  // 9 · MAIN 3/8 (branch: Next skips to idx 14, Learn more drills in)
  {
    element: "#expanded-utilities nz-space-compact",
    popover: {
      title: "Toolbar · canvas utilities",
      description:
        "Tools for keeping the canvas tidy as your workflow grows — layers, layout, comments, undo / redo, and more.",
      side: "bottom",
      align: "center",
      progressText: "Step 3 / 8",
      popoverClass: "tutorial-wide-popover",
    },
    title: "Step 3 — Middle group overview",
    aiHint:
      "Middle toolbar group overview. Default Next skips past auto-layout/comment/undo/redo (idx 10-13) to the right overview; 'Learn more' drills in.",
    skipToStep: 14,
    showDetailsButton: true,
  },
  // 10 · DETAIL
  {
    element: 'button[title="auto layout"]',
    popover: {
      title: "Auto-layout",
      description: "Rearranges your operators into a tidy left-to-right pipeline.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Auto-layout",
    aiHint: "Detail (middle group): auto-layout button.",
  },
  // 11 · DETAIL
  {
    element: 'button[title="add a comment"]',
    popover: {
      title: "Add Comment",
      description: "Drops a sticky-note comment box on the canvas.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Add Comment",
    aiHint: "Detail (middle group): add-comment button.",
  },
  // 12 · DETAIL
  {
    element: 'button[title="undo"]',
    popover: {
      title: "↶ Undo",
      description: "Steps back through your last edit.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Undo",
    aiHint: "Detail (middle group): undo button.",
  },
  // 13 · DETAIL
  {
    element: 'button[title="redo"]',
    popover: {
      title: "↷ Redo",
      description: "Replays an undone edit.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Redo",
    aiHint: "Detail (middle group): redo button.",
  },
  // 14 · MAIN 4/8 (branch: Next skips to idx 18, Learn more drills in)
  {
    element: "#execution-buttons",
    popover: {
      title: "Toolbar · run controls",
      description: "Where you launch and watch your workflow execute.",
      side: "bottom",
      align: "end",
      progressText: "Step 4 / 8",
    },
    title: "Step 4 — Right group overview",
    aiHint:
      "Right toolbar group overview. Default Next skips past Computing Unit / Share / Run (idx 15-17) to the result panel; 'Learn more' drills in.",
    skipToStep: 18,
    showDetailsButton: true,
  },
  // 15 · DETAIL
  {
    element: "#texera-compute-unit-selection",
    popover: {
      title: "Computing Unit",
      description: "The worker that runs your workflow. Pick one before hitting Run.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Computing Unit",
    aiHint: "Detail (right group): computing-unit selector.",
  },
  // 16 · DETAIL
  {
    element: "#share-button",
    popover: {
      title: "Share",
      description: "Invite collaborators to view or co-edit live.",
      side: "bottom",
      align: "center",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Share",
    aiHint: "Detail (right group): share button.",
  },
  // 17 · DETAIL
  {
    element: "#run-button",
    popover: {
      title: "▶ Run",
      description: "Launches your workflow on the selected Computing Unit.",
      side: "bottom",
      align: "end",
      popoverClass: "tutorial-no-progress",
    },
    title: "Step — Run",
    aiHint: "Detail (right group): the Run button.",
  },
  // 18 · MAIN 5/8
  {
    element: 'li[data-tutorial="open-result-panel"]',
    popover: {
      title: "Result panel",
      description: "After a run, click here to see the rows and charts.",
      side: "top",
      align: "start",
      progressText: "Step 5 / 8",
    },
    title: "Step 5 — Result Panel",
    aiHint: "Orientation: result-panel toggle in the bottom-left.",
  },
  // 19 · MAIN 6/8
  {
    element: "texera-mini-map",
    popover: {
      title: "Mini-map",
      description: "Bottom-right helper for navigating large workflows.",
      side: "top",
      align: "end",
      progressText: "Step 6 / 8",
    },
    title: "Step 6 — Mini-map",
    aiHint: "Orientation: mini-map widget in the bottom-right.",
  },
  // 20 · MAIN 7/8
  {
    element: "#agent-docked-button",
    popover: {
      title: "🤖 AI Agent",
      description: "Bottom-right chat button. Ask Texera's AI to help you build, debug, or explain your workflow.",
      side: "left",
      align: "center",
      progressText: "Step 7 / 8",
    },
    title: "Step 7 — AI Agent",
    aiHint: "Orientation: docked AI Agent launcher in the bottom-right.",
  },
  // 21 · MAIN 8/8
  {
    popover: {
      title: "Ready to build?",
      description: "That's the layout. Click <b>Done</b> when you're ready.",
      progressText: "Step 8 / 8",
    },
    title: "Step 8 — Hand-off",
    aiHint:
      "Final narration. Done triggers the chain-confirmation popover that asks the user whether to launch build-simple.",
  },
];

export const OVERVIEW_WORKSPACE_FLOW: TutorialFlow = {
  id: "overview-workspace",
  name: "Workspace overview",
  shortDesc: "8-step tour of the workspace. Each toolbar group offers an optional deep dive into individual icons.",
  difficulty: "intro",
  estimatedMinutes: 2,
  badge: {
    id: "explorer",
    emoji: "🧭",
    name: "Explorer",
    description: "Took the workspace overview tour and learned where everything lives.",
    hue: "#52c41a",
  },
  steps: STEPS,
  chainTo: "build-simple",
};
