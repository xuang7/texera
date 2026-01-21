---
title: "Bar Charts"
description: ""
weight: 10
categories: [Texera]
tags: [visualization, plotly, charts]
bookCollapseSection: true
---

### Overview
The **Bar Chart** operator visualizes a numeric value against a categorical field and renders an interactive Plotly bar chart. It optionally colors bars by a category column, supports horizontal orientation, and can apply texture patterns.

### Properties

#### Input

| Property | Required | Type | Default | Description |
|----------|----------|------|---------|-------------|
| **Value Column** | ✓ | Integer / Long / Double | - | The numeric value associated with each category. |
| **Fields** | ✓ | String / Categorical | - | Categorical column used to form bar groups. |
| **Category Column** |  | String / Categorical | No Selection | Optional — color bars by category. |
| **Horizontal Orientation** |  | Boolean | `false` | Render horizontal bars. |
| **Pattern** |  | String / Categorical | (empty) | Optional — add texture pattern based on an attribute column. |

{{< alert color="info" title="Notes" >}}
- Rows with missing `Value` or `Fields` are automatically dropped
- Returns error if `Fields` and `Value` are the same column
  {{< /alert >}}

#### Output

| Output | Type | Description |
| --- | --- | --- |
| **Output Port 1** | Table (single snapshot) | One-row output containing the generated visualization HTML. |

| Output Column | Type | Description |
| --- | --- | --- |
| **html-content** | String | HTML content for the Plotly bar chart (or an HTML error message). |


### Example
Using the Iris dataset to create a bar chart.

<div style="display: flex; gap: 24px; align-items: flex-start;">

{{< figure src="/images/barchart-input.gif" width="500" >}}

<aside style="font-size: 0.9em; color: #666; line-height: 1.8;">

1. Connect data source
2. Set **Fields** → `Species`
3. Set **Value** → `SepalLengthCm`
4. Run workflow

</aside>
</div>

#### Output

Run the workflow to see the output:

{{< figure src="/images/barchart-result.png" width="600" >}}
