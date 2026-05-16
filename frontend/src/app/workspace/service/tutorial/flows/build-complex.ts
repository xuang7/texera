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
 * Premise: the tour loads a prebuilt CSVFileScan -> Filter starter workflow
 * (movies-filter.json) into the canvas before the first step runs. This flow
 * only teaches the visualization addition — wiring a Dot Plot onto the
 * Filter's output. The ML side was split off into `build-ml`.
 */
const STEPS: TutorialStep[] = [
  {
    popover: {
      title: "Ready for more operators? 🚀",
      description:
        "Time for a bit more analysis. We prepared a CSV + Filter starter on the canvas — click Next and we'll walk through them, then add a chart on top to actually see the data.",
    },
    title: "Step 1 — Add-chart intro",
    aiHint:
      "Intro for the chart-adding flow. The tour auto-loaded a CSV File Scan + Filter starter workflow on the canvas. The next two steps spotlight each prebuilt operator with a one-line explanation, then the flow guides the user to add a Dot Plot on top.",
  },
  {
    element: '[model-id^="CSVFileScan-operator-"]',
    popover: {
      title: "Source: CSV File Scan",
      description:
        "This one reads rows from a CSV file. We pointed it at a <b>movies dataset</b>, so it's emitting one row per movie.",
      side: "bottom",
      align: "center",
    },
    title: "Step 2 — Meet the source",
    aiHint:
      "Spotlight on the prebuilt CSV File Scan operator on the canvas, with a one-line explanation that it's reading a movies CSV. No interaction needed — user clicks Next.",
  },
  {
    element: '[model-id^="Filter-operator-"]',
    popover: {
      title: "Transform: Filter",
      description:
        "Right after CSV, this <b>Filter</b> keeps only rows where <code>year &gt; 1960</code> — so the chart later only shows the modern era.",
      side: "bottom",
      align: "center",
    },
    title: "Step 3 — Meet the filter",
    aiHint:
      "Spotlight on the prebuilt Filter operator with its single predicate (year > 1960) explained. No interaction needed — user clicks Next.",
  },
  {
    element: 'nz-collapse-panel[data-group-name="Visualization"]',
    popover: {
      title: "Open the Visualization category",
      description:
        'On the left, click <b>"Visualization"</b>. This category holds every chart Texera supports: bar, line, pie, scatter, and more exotic ones.',
      side: "right",
      align: "start",
    },
    title: "Step 4 — Visualization category",
    aiHint: "The user is expanding the 'Visualization' group in the operator menu so they can find chart operators.",
    advanceOnClick: true,
  },
  {
    element: 'nz-collapse-panel[data-group-name="Basic"]',
    popover: {
      title: "Then the Basic sub-group",
      description:
        'Visualization is split into sub-groups. Click <b>"Basic"</b> to find the everyday charts (bar, line, pie).',
      side: "right",
      align: "start",
    },
    title: "Step 5 — Basic sub-group",
    aiHint:
      "The Visualization group has nested sub-groups. The user is opening 'Basic' to reveal Bar/Line/Pie chart operators.",
    advanceOnClick: true,
  },
  {
    element: '.operator-label[data-operator-type="DotPlot"]',
    popover: {
      title: "Drag Dot Plot onto the canvas",
      description:
        "Grab <b>Dot Plot</b> and drop it on the canvas, somewhere to the right of your <b>Filter</b> box. We're going to feed Filter's output into it.",
      side: "right",
      align: "start",
    },
    title: "Step 6 — Drag Dot Plot",
    aiHint:
      "The user drags the Dot Plot visualization operator onto the canvas. It will be connected to Filter's output in the next step.",
    autoAdvanceOn: "operatorAdded",
  },
  {
    element: "texera-workflow-editor",
    popover: {
      title: "Wire Filter into the chart",
      description:
        "Drag from the <b>output port</b> on the right edge of <b>Filter</b> over to the <b>input port</b> on the left edge of <b>Dot Plot</b>. Now the chart will receive the filtered rows.",
      side: "over",
      align: "center",
    },
    title: "Step 7 — Connect Filter to Dot Plot",
    aiHint:
      "The user is dragging an edge from Filter's output to Dot Plot's input so the chart visualizes the filtered rows.",
    autoAdvanceOn: "linkAdded",
  },
  {
    element: '[model-id^="DotPlot-operator-"]',
    popover: {
      title: "Click the chart to configure it",
      description:
        "Click the <b>Dot Plot</b> box. The property panel on the right will switch to its settings so you can pick which column to count.",
      side: "bottom",
      align: "center",
    },
    title: "Step 8 — Select Dot Plot",
    aiHint:
      "The user clicks the Dot Plot operator on the canvas so the property panel switches to its single-field config (Count Attribute).",
    autoAdvanceOn: "operatorSelected",
  },
  {
    element: 'li[data-tutorial="open-property-panel"]',
    popover: {
      title: "Open the property panel",
      description:
        "If the panel collapsed, click the <b>form icon</b> at the top-right to reopen it. <i>(Already open? Skipping.)</i>",
      side: "left",
      align: "center",
    },
    title: "Step 9 — Reopen property panel",
    aiHint:
      "The user reopens the property panel for the Dot Plot operator. Auto-skipped when the panel is already open.",
    advanceOnClick: true,
  },
  {
    element: ".property-editor-form",
    popover: {
      title: "Pick the column",
      description:
        "In the highlighted form, set the only required field:<br>• <b>Count Attribute</b>: <code>runtime</code><br>This will show one dot per runtime value, sized by how many movies share it. Click <b>Next</b> once set.",
      side: "left",
      align: "center",
    },
    title: "Step 10 — Configure Dot Plot",
    aiHint:
      "The user fills Dot Plot's single required field: Count Attribute = 'runtime'. The field renders as an nz-select dropdown of column names (AutofillAttributeName), and after selection the operator groups by runtime and counts rows. Spotlight is on .property-editor-form; styles.scss handles overflow / cdk-overlay z-index so the dropdown opens normally.",
    autoAdvanceOn: "propertyChanged",
  },
  {
    element: "#run-button",
    popover: {
      title: "Run it",
      description: "Hit <b>Run</b>. Texera will pipe the rows through Filter into Dot Plot and render it.",
      side: "bottom",
      align: "start",
    },
    title: "Step 11 — Run the workflow",
    aiHint: "The user clicks the Run button to execute the workflow with the new Dot Plot.",
    advanceOnClick: true,
  },
  {
    element: 'li[data-tutorial="open-result-panel"]',
    popover: {
      title: "Open the result panel",
      description:
        "Click the <b>square icon</b> at the bottom-left to expand the result panel. Then click the Dot Plot on the canvas to see its rendered output.",
      side: "top",
      align: "start",
    },
    title: "Step 12 — Open result panel",
    aiHint: "The user clicks the 'Open Result Panel' icon at the bottom-left to expand the data panel.",
    advanceOnClick: true,
  },
  {
    popover: {
      title: "Chart unlocked",
      description:
        "Your data is now visual. Click any operator to inspect its output in the result panel. When you're ready for the next level, try the <b>Add a simple ML model</b> tour next.",
    },
    title: "Step 13 — Chart celebration",
    aiHint:
      "Final step. The user has wired a Dot Plot off Filter and run the workflow. No element highlighted, centered celebratory popover that teases the ML follow-up tour.",
  },
];

export const BUILD_COMPLEX_FLOW: TutorialFlow = {
  id: "build-complex",
  name: "Add a chart",
  shortDesc: "Plug a Dot Plot onto a pre-built CSV + Filter workflow and see the data.",
  difficulty: "medium",
  estimatedMinutes: 2,
  prerequisites: ["build-simple"],
  prebuiltWorkflow: "movies-filter",
  badge: {
    id: "chart-maker",
    emoji: "📊",
    name: "Chart Maker",
    description: "Visualized a Texera workflow with your first chart.",
    hue: "#13c2c2",
  },
  steps: STEPS,
};
