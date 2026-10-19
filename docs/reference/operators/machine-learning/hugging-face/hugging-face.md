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
title: "Hugging Face"
description: "Call a Hugging Face model via the Inference API"
category: "Hugging Face"
operator_type: "HuggingFace"
tags: [machine-learning, hugging-face]
---

[Home](../../../) > [Machine Learning](../../) > [Hugging Face](../)

### Input Properties

| Property | Requirement | Type | Default | Description |
|----------|-------------|------|---------|-------------|
| HF API Token | ✓ | String | - | Your Hugging Face API token (from<br>https://huggingface.co/settings/tokens) |
| Task | ✓ | String | text-generation | The Hugging Face pipeline task type |
| Model | ✓ | String | Qwen/Qwen2.5-72B-Instruct | Select a Hugging Face model |
| Prompt Column | ✓ | String | - | Column in the input table to use as the user<br>prompt |
| Image Upload |  | String | - | Upload an image for Hugging Face image tasks |
| Input Image Column |  | String | - | Column containing image data from the input table |
| Audio Upload |  | String | - | Upload audio for Hugging Face audio tasks |
| Input Audio Column |  | String | - | Column containing audio data from the input table |
| Context Column |  | String | - | Column containing the context passage for<br>question answering |
| Candidate Labels |  | String | - | Comma-separated candidate labels for zero-shot<br>classification |
| Sentences Column |  | String | - | Column with comma-separated sentences for<br>sentence similarity and text ranking |
| System Prompt |  | String | You are a helpful assistant. | Optional system message to set model behavior |
| Max New Tokens |  | Integer | 256 | Maximum number of tokens to generate (1-4096) |
| Temperature |  | Double | 0.7 | Sampling temperature (0.0 = deterministic, up to<br>2.0) |
| Result Column Name |  | String | hf_response | Name of the new column added to the output table |

### Output Ports

| Port | Mode |
|------|------|
| 0 | [Set Snapshot](../../../output-modes/#set-snapshot) |
