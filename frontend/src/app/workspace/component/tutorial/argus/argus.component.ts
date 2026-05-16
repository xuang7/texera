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

import { Component, Input } from "@angular/core";
import { NgClass, NgIf } from "@angular/common";

export type ArgusState = "idle" | "thinking" | "cheer" | "wave";

/**
 * Argus — the peacock-feather eye mascot that hosts the tutorial. Inspired by
 * Argus Panoptes, whose hundred eyes were placed on the peacock's tail by Hera.
 *
 * Pure SVG, CSS-driven animations only.
 */
@Component({
  selector: "texera-argus",
  templateUrl: "argus.component.html",
  styleUrls: ["argus.component.scss"],
  imports: [NgClass, NgIf],
})
export class ArgusComponent {
  @Input() state: ArgusState = "idle";
  @Input() size = 64;
}
