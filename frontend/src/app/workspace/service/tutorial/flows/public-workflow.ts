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

import type { TutorialFlow } from "./index";

/**
 * Cross-page flow: open a public workflow from the hub, clone it, run it.
 * Stub for now — needs hub-side selectors and the cross-route resume plumbing.
 */
export const PUBLIC_WORKFLOW_FLOW: TutorialFlow = {
  id: "public-workflow",
  name: "Run a public workflow",
  shortDesc: "Browse, clone, and run a community workflow end-to-end.",
  difficulty: "easy",
  estimatedMinutes: 2,
  comingSoon: true,
  prerequisites: ["hub-tour"],
  badge: {
    id: "voyager",
    emoji: "🔭",
    name: "Community Voyager",
    description: "Cloned and ran your first public workflow.",
    hue: "#eb2f96",
  },
  steps: [],
};
