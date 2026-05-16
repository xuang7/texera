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

import { AfterViewChecked, Component, ElementRef, OnDestroy, OnInit, ViewChild } from "@angular/core";
import { NgClass, NgFor, NgIf } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { firstValueFrom, Subject, Subscription } from "rxjs";
import { takeUntil } from "rxjs/operators";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzInputDirective } from "ng-zorro-antd/input";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { TutorialService } from "../../../service/tutorial/tutorial.service";
import { ArgusComponent, ArgusState } from "../argus/argus.component";
import { WorkflowActionService } from "../../../service/workflow-graph/model/workflow-action.service";
import { WorkflowUtilService } from "../../../service/workflow-graph/util/workflow-util.service";
import { Point } from "../../../types/workflow-common.interface";
import { AgentService } from "../../../service/agent/agent.service";

interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: Date;
}

const SYSTEM_PROMPT = `You are Argus, a friendly AI tutor for Texera, a visual workflow editor for big-data analysis.
Workflows are built by dragging operators (CSVScan, Filter, KeywordSearch, ...) onto a canvas and connecting them via edges from output ports to input ports.

Style rules (important):
- Keep answers SHORT: 2-3 sentences max.
- Plain, clear English. Skip emojis entirely unless the user uses one first.
- Do NOT use em dashes (—) or en dashes (–). Use a period or comma instead.
- No exclamation marks unless genuinely celebrating completion.
- If the user is stuck on a step, give a direct hint without being condescending.
- If asked about an operator, briefly explain what it does in one or two sentences.

Tool calls (use sparingly):
You can perform an action on the user's canvas by appending ONE directive on the very last line of your reply, then stopping. The line MUST match this exact format:
  [[ACTION:add_operator {"type":"<OperatorType>"}]]
- Only emit an action when the user explicitly asks you to do something on the canvas (e.g. "add a Filter for me", "drop a Bar Chart in").
- For general questions, EXPLANATIONS, or "how do I..." prompts, DO NOT emit an action. Just answer with text.
- Examples of valid OperatorType: CSVFileScan, Filter, Limit, KeywordSearch, Sort, Unnest, Projection, BarChart, DotPlot, LineChart, PieChart, WordCloud, Histogram, ScatterPlot, SklearnLogisticRegression.
- Briefly mention what you did in the text BEFORE the directive ("Adding a Filter on the canvas now."). Do not write anything AFTER the directive.`;

const ACTION_REGEX = /\[\[ACTION:(\w+)\s+(\{[\s\S]*?\})\]\]/;
/**
 * Argus picks a canvas position for new operators by stacking them to the
 * right of whatever it last placed. The seed point is chosen to land in the
 * middle of the default-zoom viewport, with vertical wiggle so successive
 * adds don't all overlap on the same row when the canvas is otherwise empty.
 */
const ARGUS_PLACEMENT_SEED: Point = { x: 600, y: 260 };
const ARGUS_PLACEMENT_STEP_X = 180;

@Component({
  selector: "texera-tutorial-chat",
  templateUrl: "tutorial-chat.component.html",
  styleUrls: ["tutorial-chat.component.scss"],
  imports: [
    NgIf,
    NgFor,
    NgClass,
    FormsModule,
    NzButtonComponent,
    NzWaveDirective,
    ɵNzTransitionPatchDirective,
    NzIconDirective,
    NzInputDirective,
    NzTooltipDirective,
    ArgusComponent,
  ],
})
export class TutorialChatComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild("messageContainer") private messageContainer?: ElementRef<HTMLDivElement>;

  private readonly destroy$ = new Subject<void>();

  public isOpen = false;
  public isTutorialActive = false;
  /** Latches true the first time the user opens the chat — used to hide
   *  the one-time "ask me anything" nudge bubble. */
  public hasOpenedChatOnce = false;
  public visibleMessages: ChatMessage[] = []; // shown to user (no system msg)
  public inputText = "";
  public isSending = false;
  public initialized = false;
  /** Argus state mirrors what the agent is doing — `thinking` while a chat
   *  request is in flight, `wave` while idle (inviting the user to click). */
  public argusState: ArgusState = "wave";
  private shouldScrollToBottom = false;

  private agentId: string | null = null;
  private agentInitPromise: Promise<string | null> | null = null;
  private agentStepsSub: Subscription | null = null;
  private processedMessageIds = new Set<string>();

  constructor(
    private tutorialService: TutorialService,
    private workflowActionService: WorkflowActionService,
    private workflowUtilService: WorkflowUtilService,
    private agentService: AgentService
  ) {}

  ngOnInit(): void {
    this.tutorialService.isActive$.pipe(takeUntil(this.destroy$)).subscribe(active => {
      this.isTutorialActive = active;
      if (!active) this.isOpen = false;
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  togglePanel(): void {
    this.isOpen = !this.isOpen;
    if (this.isOpen) this.hasOpenedChatOnce = true;
    this.argusState = this.isOpen ? "idle" : "wave";
    if (this.isOpen && !this.initialized) {
      this.visibleMessages.push({
        role: "assistant",
        content:
          "👋 Hey! I'm Argus — your Texera tutor. Stuck on a step? Curious what an operator does? Type a question below — I'll keep it short and useful.",
        timestamp: new Date(),
      });
      this.shouldScrollToBottom = true;
      this.initialized = true;
    }
  }

  closePanel(): void {
    this.isOpen = false;
    this.argusState = "wave";
  }

  async sendMessage(): Promise<void> {
    const text = this.inputText.trim();
    if (!text || this.isSending) return;

    this.visibleMessages.push({ role: "user", content: text, timestamp: new Date() });
    this.shouldScrollToBottom = true;
    this.inputText = "";
    this.isSending = true;
    this.argusState = "thinking";

    const id = await this.ensureAgent();
    if (!id) {
      this.isSending = false;
      this.argusState = "idle";
      return;
    }

    const contextLines = this.buildWorkflowContext();
    const fullPrompt = [
      SYSTEM_PROMPT,
      "",
      ...contextLines,
      "",
      `User: ${text}`,
      "",
      "Reply now as Argus, following the style rules.",
    ].join("\n");

    this.agentService.sendMessage(id, fullPrompt, "chat");
  }

  /**
   * Lazily create + activate a dedicated agent-service agent on first use.
   * Prefers claude-haiku-4.5 (per bin/litellm-config.yaml), falls back to
   * the first model the gateway exposes.
   */
  private ensureAgent(): Promise<string | null> {
    if (this.agentId) return Promise.resolve(this.agentId);
    if (this.agentInitPromise) return this.agentInitPromise;

    this.agentInitPromise = (async () => {
      try {
        const models = await firstValueFrom(this.agentService.fetchModelTypes());
        if (!models.length) {
          this.pushSystem("⚠️ No models available. Is LiteLLM + agent-service running?");
          return null;
        }
        const preferred = models.find(m => /haiku/i.test(m.id)) ?? models[0];
        const workflowId = this.workflowActionService.getWorkflowMetadata()?.wid;
        const info = await firstValueFrom(this.agentService.createAgent(preferred.id, "Argus (tutorial)", workflowId));
        this.agentId = info.id;
        this.agentService.activateAgent(info.id);
        this.agentStepsSub = this.agentService
          .getReActStepsObservable(info.id)
          .pipe(takeUntil(this.destroy$))
          .subscribe(steps => this.handleAgentSteps(steps));
        // Give the WebSocket time to finish CONNECTING → init handshake.
        await new Promise(resolve => setTimeout(resolve, 800));
        return info.id;
      } catch (err: any) {
        this.pushSystem(`⚠️ Couldn't start Argus: ${err?.message ?? "agent-service unreachable"}`);
        return null;
      }
    })();
    return this.agentInitPromise;
  }

  private handleAgentSteps(steps: any[]): void {
    if (!steps?.length) return;
    for (const step of steps) {
      if (step.role !== "agent" || !step.isEnd) continue;
      if (this.processedMessageIds.has(step.messageId)) continue;
      this.processedMessageIds.add(step.messageId);

      const parsed = this.parseReply(step.content ?? "");
      this.visibleMessages.push({ role: "assistant", content: parsed.text, timestamp: new Date() });
      this.shouldScrollToBottom = true;
      if (parsed.action) {
        const note = this.executeAction(parsed.action.name, parsed.action.params);
        if (note) this.pushSystem(note);
      }
      this.isSending = false;
      this.argusState = "cheer";
      setTimeout(() => (this.argusState = "idle"), 800);
    }
  }

  onKeyEnter(event: KeyboardEvent): void {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  private pushSystem(content: string): void {
    this.visibleMessages.push({ role: "system", content, timestamp: new Date() });
    this.shouldScrollToBottom = true;
  }

  /**
   * Gather the tutorial step + canvas state into a few context lines the LLM
   * can read at the top of the prompt. Keeps the model grounded in what's
   * actually on the canvas instead of guessing.
   */
  private buildWorkflowContext(): string[] {
    const step = this.tutorialService.currentStep;
    const graph = this.workflowActionService.getTexeraGraph();
    const allOps = graph.getAllOperators();
    const highlighted = this.workflowActionService.getJointGraphWrapper().getCurrentHighlightedOperatorIDs();
    const selectedOp = highlighted.length === 1 ? graph.getOperator(highlighted[0]) : null;

    const stepLine = step
      ? `[Tutorial step] ${step.title} — ${step.aiHint}`
      : "[Tutorial step] (none — the user is exploring on their own)";
    const canvasLine = allOps.length
      ? `[Canvas operators] ${allOps.map(o => `${o.operatorType}#${o.operatorID.slice(-6)}`).join(", ")}`
      : "[Canvas operators] (empty)";
    const selectedLine = selectedOp
      ? `[Selected operator] ${selectedOp.operatorType} | properties: ${JSON.stringify(selectedOp.operatorProperties)}`
      : "[Selected operator] (none)";
    return [stepLine, canvasLine, selectedLine];
  }

  /**
   * Pull an optional action directive off the tail of the LLM reply. The
   * directive format is `[[ACTION:name {json}]]`; the function returns the
   * stripped user-facing text plus the parsed action (if any).
   */
  private parseReply(reply: string): { text: string; action?: { name: string; params: any } } {
    const match = reply.match(ACTION_REGEX);
    if (!match) return { text: reply };
    try {
      const params = JSON.parse(match[2]);
      return {
        text: reply.replace(ACTION_REGEX, "").trim(),
        action: { name: match[1], params },
      };
    } catch {
      // Malformed JSON — drop the directive but keep the text so the user
      // still sees Argus's explanation.
      return { text: reply.replace(ACTION_REGEX, "").trim() };
    }
  }

  /**
   * Run an Argus-emitted action against the workflow. Returns a short
   * confirmation / error string to surface in the chat as a system message.
   */
  private executeAction(name: string, params: any): string | null {
    if (name === "add_operator") {
      return this.executeAddOperator(params?.type);
    }
    return `(Unknown action: ${name})`;
  }

  private executeAddOperator(operatorType: string | undefined): string | null {
    if (!operatorType || typeof operatorType !== "string") {
      return "(Argus tried to add an operator but didn't say which type.)";
    }
    try {
      const predicate = this.workflowUtilService.getNewOperatorPredicate(operatorType);
      const point = this.pickAddPosition();
      this.workflowActionService.addOperator(predicate, point);
      return `Added ${operatorType} on the canvas.`;
    } catch (err: any) {
      return `Couldn't add ${operatorType}: ${err?.message ?? "operator type not found"}`;
    }
  }

  /**
   * Pick a position for a newly-added operator: start at the seed point,
   * shift right by one step for each operator already on the canvas so they
   * don't stack on top of each other.
   */
  private pickAddPosition(): Point {
    const count = this.workflowActionService.getTexeraGraph().getAllOperators().length;
    return {
      x: ARGUS_PLACEMENT_SEED.x + count * ARGUS_PLACEMENT_STEP_X,
      y: ARGUS_PLACEMENT_SEED.y,
    };
  }

  private scrollToBottom(): void {
    if (this.messageContainer) {
      const el = this.messageContainer.nativeElement;
      el.scrollTop = el.scrollHeight;
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.agentStepsSub?.unsubscribe();
    if (this.agentId) {
      this.agentService.deactivateAgent(this.agentId);
    }
  }
}
