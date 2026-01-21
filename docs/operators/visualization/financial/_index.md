---
title: "Financial Charts"
description: "Time-series price action and range/interval analysis."
weight: 10
categories: [Texera]
tags: [visualization, plotly, charts]
---

Charts for financial data and business metrics.

| Operator | Description |
|----------|-------------|
| [Bullet Chart](#bullet-chart) | Actual vs target comparison |
| [Candlestick Chart](#candlestick-chart) | OHLC price data |
| [Funnel Plot](#funnel-plot) | Stage-based conversion |
| [Waterfall Chart](#waterfall-chart) | Cumulative effect breakdown |

---

## Bullet Chart

### Overview

The **Bullet Chart** operator compares actual values against targets with qualitative ranges.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Actual** | ✓ | - | Current value column. |
| **Target** | ✓ | - | Target value column. |
| **Ranges** | | - | Qualitative range columns. |

---

## Candlestick Chart

### Overview

The **Candlestick Chart** operator displays OHLC (Open, High, Low, Close) price data.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Date** | ✓ | - | Date/time column. |
| **Open** | ✓ | - | Opening price column. |
| **High** | ✓ | - | High price column. |
| **Low** | ✓ | - | Low price column. |
| **Close** | ✓ | - | Closing price column. |

---

## Funnel Plot

### Overview

The **Funnel Plot** operator visualizes stage-based conversion data.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Stage** | ✓ | - | Stage name column. |
| **Value** | ✓ | - | Value at each stage. |

---

## Waterfall Chart

### Overview

The **Waterfall Chart** operator shows cumulative effect of sequential values.

### Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| **Category** | ✓ | - | Category labels. |
| **Value** | ✓ | - | Positive/negative values. |
