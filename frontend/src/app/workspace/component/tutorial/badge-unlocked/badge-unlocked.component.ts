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

import { Component, EventEmitter, Input, Output } from "@angular/core";
import { NgIf } from "@angular/common";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { ArgusComponent } from "../argus/argus.component";
import type { BadgeDef } from "../../../service/tutorial/flows";

@Component({
  selector: "texera-badge-unlocked",
  templateUrl: "badge-unlocked.component.html",
  styleUrls: ["badge-unlocked.component.scss"],
  imports: [NgIf, NzButtonComponent, NzWaveDirective, ɵNzTransitionPatchDirective, ArgusComponent],
})
export class BadgeUnlockedComponent {
  @Input() badge: BadgeDef | null = null;
  @Input() flowName: string = "";
  @Output() dismiss = new EventEmitter<void>();
  @Output() viewShelf = new EventEmitter<void>();

  close(): void {
    this.dismiss.emit();
  }

  openShelf(): void {
    this.viewShelf.emit();
  }
}
