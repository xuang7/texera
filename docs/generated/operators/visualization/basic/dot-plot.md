---
title: "Dot Plot"
description: ""
weight: 10
categories: [Texera]
tags: [visualization, plotly, charts]
bookCollapseSection: true
---
### Overview
The **Dot Plot** operator summarizes how many times each category appears in a column and visualizes the result as a horizontal dot plot. Internally, it groups by the selected attribute, counts occurrences, and renders a Plotly strip chart where dot position reflects the count.

### Properties

#### Input

| Property | Required | Type | Default | Description |
|----------|----------|------|---------|-------------|
| **Count Attribute** | ✓ | String / Categorical | - | The attribute used for grouping and counting in the dot plot (`countAttribute`). |

{{< alert color="info" title="Notes" >}}
- The operator groups by **Count Attribute** and computes a count per category.
- If the input table is empty, the operator returns an HTML error message.
  {{< /alert >}}

#### Output

| Output | Type | Description |
| --- | --- | --- |
| **Output Port 1** | Table (single snapshot) | One-row output containing the generated visualization HTML. |

| Output Column | Type | Description |
| --- | --- | --- |
| **html-content** | String | HTML content for the Plotly dot plot (or an HTML error message). |


### Example
Using the Iris dataset to create a dot plot (category frequency).

<div style="display: flex; gap: 24px; align-items: flex-start;">

{{< figure src="/images/dotplot-input.gif" width="500" >}}

<aside style="font-size: 0.9em; color: #666; line-height: 1.8;">

1. Connect the Iris dataset
2. Set **Count Attribute** → `SepalLengthCm`
3. Run workflow

</aside>
</div>

#### Output
Run the workflow to see the output:

{{< figure src="/images/dotplot-result.gif" width="600" >}}
