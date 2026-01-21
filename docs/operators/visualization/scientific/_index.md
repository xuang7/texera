---
title: "Scientific Charts"
description: "Continuous fields, structures, and spatial/graph data."
weight: 10
categories: [Texera]
tags: [visualization, plotly, charts]
---

Visualizations for scientific and spatial data analysis.

| Operator | Description |
|----------|-------------|
| [Heatmap](#heatmap) | 2D intensity/density visualization |
| [Contour Plot](#contour-plot) | Continuous field contours |
| [Network Graph](#network-graph) | Node and edge relationships |
| [Dendrogram](#dendrogram) | Hierarchical clustering tree |
| [Quiver Plot](#quiver-plot) | Vector field visualization |

---

## Heatmap

### Overview

The **Heatmap** operator displays 2D data as color-coded intensity.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **X Axis** | ✓ | - | X-axis column. |
| **Y Axis** | ✓ | - | Y-axis column. |
| **Values** | ✓ | - | Intensity values. |
| **Color Scale** | | Viridis | Color palette. |

---

## Contour Plot

### Overview

The **Contour Plot** operator shows continuous field data as contour lines.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **X Axis** | ✓ | - | X-axis column. |
| **Y Axis** | ✓ | - | Y-axis column. |
| **Z Values** | ✓ | - | Contour level values. |

---

## Network Graph

### Overview

The **Network Graph** operator visualizes nodes and edges in a graph structure.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Source** | ✓ | - | Edge source column. |
| **Target** | ✓ | - | Edge target column. |
| **Node Labels** | | - | Node label column. |

---

## Dendrogram

### Overview

The **Dendrogram** operator displays hierarchical clustering as a tree.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Data Columns** | ✓ | - | Columns for clustering. |
| **Linkage** | | ward | Clustering method. |

---

## Quiver Plot

### Overview

The **Quiver Plot** operator visualizes vector fields with arrows.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **X** | ✓ | - | X position column. |
| **Y** | ✓ | - | Y position column. |
| **U** | ✓ | - | X-direction component. |
| **V** | ✓ | - | Y-direction component. |