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

export interface TutorialProgress {
  /** In-flight flow + step. Cleared when the user finishes or fully resets. */
  current?: { flowId: string; stepIndex: number };
  /** flowId → completion record. */
  completed: Record<string, { completedAt: number; durationMs?: number }>;
  /** Earned badge ids (includes flow badges + bonus badges like speed-runner / graduate). */
  earnedBadges: string[];
  /** Total XP across all flows + replays. */
  xp: number;
  /** flowId → step indices that have already fired their micro-reward. Prevents
   *  replays from awarding XP again for already-completed steps. */
  microsEarned: Record<string, number[]>;
  /** Whether the user has dismissed (or completed) the welcome flow picker. */
  seenWelcome: boolean;
  /** Schema version — bump when shape changes; older payloads are reset. */
  version: number;
}

const STORAGE_KEY = "texera-tutorial-progress";
const CURRENT_VERSION = 1;

export const DEFAULT_PROGRESS: TutorialProgress = {
  completed: {},
  earnedBadges: [],
  xp: 0,
  microsEarned: {},
  seenWelcome: false,
  version: CURRENT_VERSION,
};

export function loadProgress(): TutorialProgress {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      // Migrate the v1 "seen" key so returning users aren't re-prompted.
      const legacy = localStorage.getItem("texera-tutorial-seen") === "1";
      return { ...DEFAULT_PROGRESS, seenWelcome: legacy };
    }
    const parsed = JSON.parse(raw) as Partial<TutorialProgress>;
    if (parsed.version !== CURRENT_VERSION) {
      // Older schema — reset gracefully but keep the seen flag if present.
      return { ...DEFAULT_PROGRESS, seenWelcome: !!parsed.seenWelcome };
    }
    return { ...DEFAULT_PROGRESS, ...parsed };
  } catch {
    return { ...DEFAULT_PROGRESS };
  }
}

export function saveProgress(p: TutorialProgress): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(p));
  } catch {
    /* localStorage may be unavailable in some environments */
  }
}
