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
title: "SklearnAdvancedSVC Parameters"
description: "Hyperparameters accepted by SklearnAdvancedSVC"
categories: [Parameters]
tags: [ml, sklearn, parameters]
---

[← Parameters Index](../)

## Used By

This parameter set is used by the following operators:

- [SVM Classifier](../../machine-learning/advanced-sklearn/svm-classifier/)

## Parameters

| Parameter | Type | Default | Allowed values |
|-----------|------|---------|----------------|
| C | `float` | 1.0 | >0 |
| kernel | `str` | - | rbf, linear, poly, sigmoid, precomputed |
| gamma | `(lambda value: value.strip() if value.strip() in ("scale", "auto") else float(value))` | scale | - |
| degree | `int` | 3 | >=0 |
| coef0 | `float` | 0.0 | - |
| tol | `float` | 0.001 | >0 |
| probability | `(lambda value: value.lower() == "true")` | - | false, true |
