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

import { DEFAULT_PROGRESS, loadProgress, saveProgress, TutorialProgress } from "./tutorial-progress";

const STORAGE_KEY = "texera-tutorial-progress";
const LEGACY_SEEN_KEY = "texera-tutorial-seen";

describe("tutorial-progress", () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(LEGACY_SEEN_KEY);
  });

  describe("loadProgress", () => {
    it("returns DEFAULT_PROGRESS when localStorage is empty", () => {
      expect(loadProgress()).toEqual(DEFAULT_PROGRESS);
    });

    it("migrates the legacy `texera-tutorial-seen=1` flag into seenWelcome=true", () => {
      localStorage.setItem(LEGACY_SEEN_KEY, "1");

      const progress = loadProgress();

      expect(progress.seenWelcome).toBe(true);
      expect(progress.earnedBadges).toEqual(DEFAULT_PROGRESS.earnedBadges);
      expect(progress.xp).toBe(DEFAULT_PROGRESS.xp);
    });

    it("returns DEFAULT_PROGRESS when the legacy flag is anything other than '1'", () => {
      localStorage.setItem(LEGACY_SEEN_KEY, "0");

      expect(loadProgress().seenWelcome).toBe(false);
    });

    it("resets to defaults but preserves seenWelcome when schema version is stale", () => {
      const stale = { version: 0, seenWelcome: true, earnedBadges: ["should-be-dropped"] };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(stale));

      const progress = loadProgress();

      expect(progress.seenWelcome).toBe(true);
      expect(progress.earnedBadges).toEqual([]);
      expect(progress.version).toBe(DEFAULT_PROGRESS.version);
    });

    it("returns DEFAULT_PROGRESS when localStorage holds malformed JSON", () => {
      localStorage.setItem(STORAGE_KEY, "{not-json");

      expect(loadProgress()).toEqual(DEFAULT_PROGRESS);
    });
  });

  describe("saveProgress + loadProgress round-trip", () => {
    it("persists every field unchanged", () => {
      const sample: TutorialProgress = {
        current: { flowId: "overview-workspace", stepIndex: 3 },
        completed: { "build-simple": { completedAt: 123456, durationMs: 78000 } },
        earnedBadges: ["explorer", "speed-runner"],
        xp: 240,
        microsEarned: { "build-simple": [0, 1, 2] },
        seenWelcome: true,
        version: DEFAULT_PROGRESS.version,
      };

      saveProgress(sample);

      expect(loadProgress()).toEqual(sample);
    });

    it("survives an unrelated localStorage write between save and load", () => {
      saveProgress({ ...DEFAULT_PROGRESS, xp: 50 });
      localStorage.setItem("some-other-key", "noise");

      expect(loadProgress().xp).toBe(50);
    });
  });
});
