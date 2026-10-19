<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
-->

---
title: "Text Input"
description: "Source data from manually inputted text"
category: "Data Input"
operator_type: "TextInput"
tags: [data-input]
---

[Home](../../) > [Data Input](../)

### Input Properties

| Property | Requirement | Type | Default | Description |
|----------|-------------|------|---------|-------------|
| Text | ✓ | String | - | Enter the input text. By default, each line<br>becomes one tuple |
| Attribute Type | ✓ | string, single string, integer, long,<br>double, boolean, timestamp, binary,<br>large binary | string | The output field type. Each line becomes a<br>separate tuple, except for 'single string' /<br>'binary' / 'large binary', where the entire text<br>becomes one tuple |
| Attribute Name | ✓ | String | line |  |
| Limit (lines) |  | Integer | - | Maximum number of lines to output. Leave empty to<br>read all lines. (Ignored when reading the whole<br>file as one tuple.) Hidden when `Attribute Type`<br>matches `^binary$\|^single string$` |
| Offset (lines) |  | Integer | - | Number of lines to skip from the start before<br>reading. (Ignored when reading the whole file as<br>one tuple.) Hidden when `Attribute Type` matches<br>`^binary$\|^single string$` |

### Output Ports

| Port | Mode |
|------|------|
| 0 | [Set Snapshot](../../output-modes/#set-snapshot) |
