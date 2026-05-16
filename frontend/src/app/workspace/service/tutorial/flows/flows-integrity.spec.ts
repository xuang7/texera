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

import { BONUS_BADGES, FLOWS, getFlowById, GRADUATE_BADGE, SPEED_RUNNER_BADGE, TutorialFlow } from "./index";

describe("FLOWS registry integrity", () => {
  describe("identifiers", () => {
    it("every flow has a unique id", () => {
      const ids = FLOWS.map(f => f.id);
      expect(new Set(ids).size).toBe(ids.length);
    });

    it("every flow's badge id is unique across all flows + bonus badges", () => {
      const ids = [...FLOWS.map(f => f.badge.id), ...BONUS_BADGES.map(b => b.id)];
      const seen = new Set<string>();
      const dupes: string[] = [];
      for (const id of ids) {
        if (seen.has(id)) dupes.push(id);
        seen.add(id);
      }
      expect(dupes).toEqual([]);
    });

    it("getFlowById returns the matching flow", () => {
      for (const flow of FLOWS) {
        expect(getFlowById(flow.id)).toBe(flow);
      }
    });

    it("getFlowById returns null for an unknown id", () => {
      expect(getFlowById("does-not-exist")).toBeNull();
    });
  });

  describe("step content", () => {
    it("every step in every launchable flow has a non-empty title and aiHint", () => {
      const offenders: string[] = [];
      for (const flow of FLOWS.filter(f => !f.comingSoon && f.steps.length > 0)) {
        flow.steps.forEach((step, i) => {
          if (!step.title) offenders.push(`${flow.id}[${i}].title`);
          if (!step.aiHint) offenders.push(`${flow.id}[${i}].aiHint`);
        });
      }
      expect(offenders).toEqual([]);
    });

    it("flows marked comingSoon have empty steps arrays (convention check)", () => {
      const violations = FLOWS.filter(f => f.comingSoon && f.steps.length > 0).map(f => f.id);
      expect(violations).toEqual([]);
    });
  });

  describe("skipToStep targets", () => {
    it("every skipToStep value points to a strictly later step inside the same flow", () => {
      const offenders: string[] = [];
      for (const flow of FLOWS) {
        flow.steps.forEach((step, i) => {
          if (step.skipToStep === undefined) return;
          if (step.skipToStep <= i || step.skipToStep >= flow.steps.length) {
            offenders.push(`${flow.id}[${i}] → ${step.skipToStep}`);
          }
        });
      }
      expect(offenders).toEqual([]);
    });

    it("skipToStep is only used together with showDetailsButton (paired branching)", () => {
      const unpaired: string[] = [];
      for (const flow of FLOWS) {
        flow.steps.forEach((step, i) => {
          if (step.skipToStep !== undefined && step.showDetailsButton !== true) {
            unpaired.push(`${flow.id}[${i}]`);
          }
        });
      }
      expect(unpaired).toEqual([]);
    });
  });

  describe("chainTo references", () => {
    it("every chainTo points at a real flow id", () => {
      const broken: string[] = [];
      for (const flow of FLOWS) {
        if (flow.chainTo && getFlowById(flow.chainTo) === null) {
          broken.push(`${flow.id} → ${flow.chainTo}`);
        }
      }
      expect(broken).toEqual([]);
    });

    it("no flow chains to itself", () => {
      const selfLoops = FLOWS.filter(f => f.chainTo === f.id).map(f => f.id);
      expect(selfLoops).toEqual([]);
    });
  });

  describe("prerequisites", () => {
    it("every prerequisite id resolves to a real flow", () => {
      const broken: string[] = [];
      for (const flow of FLOWS) {
        for (const prereq of flow.prerequisites ?? []) {
          if (getFlowById(prereq) === null) broken.push(`${flow.id} ← ${prereq}`);
        }
      }
      expect(broken).toEqual([]);
    });
  });

  describe("bonus badges", () => {
    it("BONUS_BADGES contains SPEED_RUNNER and GRADUATE", () => {
      expect(BONUS_BADGES).toContain(SPEED_RUNNER_BADGE);
      expect(BONUS_BADGES).toContain(GRADUATE_BADGE);
    });
  });
});

describe("overview-workspace branching", () => {
  let flow: TutorialFlow;

  beforeAll(() => {
    const found = getFlowById("overview-workspace");
    expect(found).not.toBeNull();
    flow = found!;
  });

  it("every group-overview step jumps over at least one detail step", () => {
    const offenders: string[] = [];
    flow.steps.forEach((step, index) => {
      if (step.skipToStep === undefined) return;
      if (step.skipToStep <= index + 1) {
        offenders.push(`${flow.id}[${index}] → ${step.skipToStep} (no skip)`);
      }
      const target = flow.steps[step.skipToStep];
      if (!target?.title) offenders.push(`${flow.id}[${step.skipToStep}] has no title`);
    });
    expect(offenders).toEqual([]);
  });
});
