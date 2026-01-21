---
title: "Bubble Charts"
description: ""
weight: 10
categories: [Texera]
tags: [visualization, plotly, charts]
bookCollapseSection: true
---

### Overview
The **Bubble Chart** operator creates a scatter plot where each point is drawn at (X-Column, Y-Column), and its bubble size is determined by a third numeric column (Z-Column). Optionally, bubbles can be colored by category to reveal clusters or groups.

### Properties

#### Input
| Property | Required | Type | Default | Description |
|----------|----------|------|---------|-------------|
| **X-Column** | ✓ | Numeric (recommended) | - | Data column for the x-axis (`xValue`). |
| **Y-Column** | ✓ | Numeric (recommended) | - | Data column for the y-axis (`yValue`). |
| **Z-Column** | ✓ | Numeric | - | Data column to determine bubble size (`zValue`). |
| **Enable Color** |  | Boolean | `false` | When enabled, color bubbles using Color-Column (`enableColor`). |
| **Color-Column** | Conditional | Categorical / String | - | Column used to color bubbles (`colorCategory`). Required only when Enable Color = true. |

{{< alert color="info" title="Notes" >}}
- Rows with missing values in **X-Column**, **Y-Column**, or **Z-Column** are dropped before plotting.
- If the table becomes empty after cleaning (e.g., every row has at least one missing value), the operator returns an error message.
- **Z-Column** controls bubble size; use a numeric column for meaningful size scaling.
  {{< /alert >}}

#### Output

| Output | Type | Description |
| --- | --- | --- |
| **Output Port 1** | Table (single snapshot) | One-row output containing the generated visualization HTML. |

| Output Column | Type | Description |
| --- | --- | --- |
| **html-content** | String | HTML content for the Plotly bubble chart (or an HTML error message). |

### Example
Using the Iris dataset to create a bubble chart.

<div style="display: flex; gap: 24px; align-items: flex-start;">

{{< figure src="/images/bubblechart-input.gif" width="500" >}}

<aside style="font-size: 0.9em; color: #666; line-height: 1.8;">

1. Connect the Iris dataset
2. Set **X-Column** → `SepalLengthCm`
3. Set **Y-Column** → `SepalWidthCm`
4. Set **Z-Column** → `PetalLengthCm` (bubble size)
5. Set **Color-Column** → `Species`
6. Run workflow

</aside>
</div>

#### Output
Run the workflow to see the output:

{{< figure src="/images/bubblechart-result.png" width="600" >}}