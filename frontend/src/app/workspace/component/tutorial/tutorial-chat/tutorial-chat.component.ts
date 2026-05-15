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
import { HttpClient } from "@angular/common/http";
import { Subject } from "rxjs";
import { takeUntil } from "rxjs/operators";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzInputDirective } from "ng-zorro-antd/input";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { TutorialService } from "../../../service/tutorial/tutorial.service";

interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: Date;
}

interface ChatCompletionResponse {
  choices: { message: { role: string; content: string } }[];
}

const SYSTEM_PROMPT = `You are a friendly AI tutor for Texera, a visual workflow editor for big-data analysis.
Workflows are built by dragging operators (CSVScan, Filter, KeywordSearch, ...) onto a canvas and connecting them via edges from output ports to input ports.
Keep your answers SHORT (2-3 sentences max). If the user is stuck on a tutorial step, give a direct hint without being condescending. If asked about an operator, briefly explain what it does.`;

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
  ],
})
export class TutorialChatComponent implements OnInit, OnDestroy, AfterViewChecked {
  @ViewChild("messageContainer") private messageContainer?: ElementRef<HTMLDivElement>;

  private readonly destroy$ = new Subject<void>();

  public isOpen = false;
  public isTutorialActive = false;
  public visibleMessages: ChatMessage[] = []; // shown to user (no system msg)
  public inputText = "";
  public isSending = false;
  public initialized = false;
  private shouldScrollToBottom = false;

  constructor(
    private http: HttpClient,
    private tutorialService: TutorialService
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
    if (this.isOpen && !this.initialized) {
      this.visibleMessages.push({
        role: "assistant",
        content: "👋 Hey! I'm your Texera tutor. Stuck on a step? Curious what an operator does? Type a question below — I'll keep it short and useful.",
        timestamp: new Date(),
      });
      this.shouldScrollToBottom = true;
      this.initialized = true;
    }
  }

  closePanel(): void {
    this.isOpen = false;
  }

  sendMessage(): void {
    const text = this.inputText.trim();
    if (!text || this.isSending) return;

    this.visibleMessages.push({ role: "user", content: text, timestamp: new Date() });
    this.shouldScrollToBottom = true;
    this.inputText = "";
    this.isSending = true;

    const step = this.tutorialService.currentStep;
    const contextLine = step
      ? `[Tutorial context: the user is on ${step.title}. Hint context: ${step.aiHint}]`
      : `[Tutorial context: the user is exploring Texera.]`;

    // Build chat history for the LLM (system + visible turns, with current context injected)
    const messages: ChatMessage[] = [
      { role: "system", content: `${SYSTEM_PROMPT}\n\n${contextLine}` },
      ...this.visibleMessages
        .filter(m => m.role === "user" || m.role === "assistant")
        .map(m => ({ role: m.role, content: m.content })),
    ];

    this.http
      .post<ChatCompletionResponse>("/api/chat/completion", {
        model: "claude-haiku-4.5",
        messages,
        max_tokens: 400,
      })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          const reply = response.choices?.[0]?.message?.content ?? "(no response)";
          this.visibleMessages.push({ role: "assistant", content: reply, timestamp: new Date() });
          this.shouldScrollToBottom = true;
          this.isSending = false;
        },
        error: err => {
          const errMsg = err?.error?.error?.message || err?.message || "Failed to get response";
          this.visibleMessages.push({
            role: "assistant",
            content: `⚠️ ${errMsg}`,
            timestamp: new Date(),
          });
          this.shouldScrollToBottom = true;
          this.isSending = false;
        },
      });
  }

  onKeyEnter(event: KeyboardEvent): void {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
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
  }
}
