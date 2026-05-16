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

const STEPS: TutorialStep[] = [
  {
    popover: {
      title: "Let's build your first workflow",
      description:
        "We'll wire a tiny pipeline together: <b>CSV File Scan</b> → <b>Limit</b> → Run → see the rows. Should take about 2 minutes. Ready? Click Next.",
    },
    title: "Step 1 — Intro",
    aiHint:
      "Brief intro for build-simple. Assumes the user has seen (or will skip) the workspace overview tour, so this step does NOT dwell on what the operator panel is — it just states the goal (CSV -> Limit -> Run -> view) and starts. No element highlighted, centered popover.",
  },
  {
    element: 'nz-collapse-panel[data-group-name="Data Input"]',
    popover: {
      title: "First, let's get some data",
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
      title: "Drag it onto the canvas",
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
      title: "Open its settings",
      description:
        "See the little <b>form icon</b> at the top-right? <b>Click it</b> to slide open the settings panel on the right side. <i>(Already open? We'll just skip ahead.)</i>",
      side: "left",
      align: "center",
    },
    title: "Step 5 — Open the Property Panel",
    aiHint:
      "The user needs to click the small form icon at the top-right of the canvas to expand the (currently collapsed) property panel. Auto-skipped when the panel is already open.",
    advanceOnClick: true,
  },
  {
    element: "#formly-title",
    popover: {
      title: "This is the property panel",
      description:
        "Every operator's settings live here. For <b>CSV File Scan</b>, the only thing we need is to pick a file. Click Next.",
      side: "left",
      align: "center",
    },
    title: "Step 6 — Meet the property panel",
    aiHint:
      "Always-shown explainer that introduces the property panel. Runs whether the previous open-property-panel step was clicked or auto-skipped, so users who already had the panel open still get the orientation.",
  },
  {
    element: 'button[data-tutorial="file-select-button"]',
    popover: {
      title: "Pick a file",
      description: "Click <b>Select File</b> to open the dataset browser — that's where your CSVs live.",
      side: "left",
      align: "center",
    },
    title: "Step 7 — Open the File Picker",
    aiHint: "The user is about to click the Select File button to open the dataset selection modal.",
    advanceOnClick: true,
  },
  {
    element: ".ant-modal-content",
    popover: {
      title: "Browse your data",
      description:
        "Pick a <b>dataset</b> on the left, expand a version, then <b>double-click any .csv</b>. The modal closes itself and the file name auto-fills — no typing required.",
      side: "top",
      align: "end",
    },
    title: "Step 8 — Pick a CSV File",
    aiHint:
      "The user is choosing a CSV file from the dataset selection modal. After they pick one, the modal closes and the fileName property updates.",
    autoAdvanceOn: "propertyChanged",
  },
  {
    element: 'nz-collapse-panel[data-group-name="Data Cleaning"]',
    popover: {
      title: "Time for a second operator",
      description:
        'Back to the <b>left panel</b>. Click <b>"Data Cleaning"</b> to open that category — we\'re about to chain a second operator after our source.',
      side: "right",
      align: "start",
    },
    title: "Step 9 — Find the Data Cleaning Category",
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
    title: "Step 10 — Drag Limit",
    aiHint: "The user is dragging the Limit operator onto the canvas as a second operator.",
    autoAdvanceOn: "operatorAdded",
  },
  {
    element: "texera-workflow-editor",
    popover: {
      title: "Wire them up",
      description:
        'Drag from the <b>▶ port on the right edge of CSV File Scan</b> over to the <b>port on the left edge of Limit</b>. That edge tells Texera "send rows this way".',
      side: "over",
      align: "center",
    },
    title: "Step 11 — Connect Operators",
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
    title: "Step 12 — Click Limit",
    aiHint:
      "The user needs to click the just-connected Limit operator on the canvas to switch the property editor to its fields.",
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
    title: "Step 13 — Open the Property Panel (Limit)",
    aiHint:
      "Same as step 5: the user clicks the form icon to reopen the property panel for the Limit operator. Auto-skipped when the panel is already open.",
    advanceOnClick: true,
  },
  {
    element: ".property-editor-form",
    popover: {
      title: "How many rows?",
      description:
        "Type a number into the <b>Limit</b> field — that's the max rows you'll see. <b>Try 10</b> for a snappy preview.",
      side: "left",
      align: "start",
    },
    title: "Step 14 — Configure Limit",
    aiHint: "The user is configuring the Limit operator by typing a number in the limit field (max output rows).",
    autoAdvanceOn: "propertyChanged",
  },
  {
    element: "#texera-compute-unit-selection",
    popover: {
      title: "Almost there — pick a Computing Unit",
      description:
        "Before running, click the <b>Computing Unit</b> selector at the top and pick (or start) one. That's the little worker that'll execute your workflow.",
      side: "bottom",
      align: "start",
    },
    title: "Step 15 — Connect a Computing Unit",
    aiHint: "The user needs to select/connect a Computing Unit from the top menu before running the workflow.",
    autoAdvanceOn: "computingUnitConnected",
  },
  {
    element: "#run-button",
    popover: {
      title: "Hit Run!",
      description:
        "Once the unit shows <b>Connected</b>, smash that <b>▶ Run</b> button and watch Texera get to work.",
      side: "bottom",
      align: "start",
    },
    title: "Step 16 — Run the Workflow",
    aiHint: "The user is clicking the Run button at the top to execute the workflow.",
    advanceOnClick: true,
  },
  {
    element: 'li[data-tutorial="open-result-panel"]',
    popover: {
      title: "Open the result panel",
      description:
        "Your workflow finished — the data is ready! <b>Click this little square icon</b> at the bottom-left to slide open the result panel and see your rows.",
      side: "top",
      align: "start",
    },
    title: "Step 17 — Open the Result Panel",
    aiHint:
      "The user just ran the workflow. They need to click the 'Open Result Panel' icon (the square / border icon at the bottom-left of the screen) to expand the result panel and view their data.",
    advanceOnClick: true,
  },
  {
    popover: {
      title: "🎉 You did it!",
      description:
        "Your CSV rows are now waiting for you in the result panel. You just built and ran your very first Texera workflow — from empty canvas to real data in under two minutes. <b>Welcome aboard!</b>",
    },
    title: "Step 18 — Celebration",
    aiHint:
      "Final celebratory step. The user has opened the result panel and can now see their workflow's output. No element is highlighted — the popover is centered like the welcome screen for a clean closing moment.",
  },
];

export const BUILD_SIMPLE_FLOW: TutorialFlow = {
  id: "build-simple",
  name: "Build your first workflow",
  shortDesc: "CSV → Limit → Run → View. The friendly 18-step intro.",
  difficulty: "intro",
  estimatedMinutes: 2,
  badge: {
    id: "workflow-builder",
    emoji: "🧱",
    name: "Workflow Builder",
    description: "Built and ran your first Texera workflow.",
    hue: "#1890ff",
  },
  steps: STEPS,
};
