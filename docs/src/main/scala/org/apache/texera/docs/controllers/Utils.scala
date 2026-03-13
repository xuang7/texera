// controllers/Utils.scala
package org.apache.texera.docs.controllers

import com.microsoft.playwright.options.{BoundingBox, WaitForSelectorState}
import com.microsoft.playwright.{Locator, Mouse, Page}

// ═══════════════════════════════════════════════════════════════════
// Utils
// ═══════════════════════════════════════════════════════════════════

object Utils {
  def waitVisible(loc: Locator): Locator = {
    loc.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
    loc
  }

  def installFakeCursor(page: Page): Unit = {
    page.addStyleTag(new Page.AddStyleTagOptions().setContent(
      """
      #pw-cursor {
        position: fixed; left: 0; top: 0;
        width: 14px; height: 14px; border-radius: 50%;
        background: rgba(255, 0, 0, 0.9);
        box-shadow: 0 0 0 3px rgba(255, 0, 0, 0.25);
        pointer-events: none; z-index: 2147483647;
        transform: translate(-50%, -50%);
      }
      .pw-click {
        position: fixed; left: 0; top: 0;
        width: 18px; height: 18px; border-radius: 50%;
        border: 3px solid rgba(255, 0, 0, 0.85);
        pointer-events: none; z-index: 2147483647;
        transform: translate(-50%, -50%);
        animation: pw-click-pop 600ms ease-out forwards;
      }
      @keyframes pw-click-pop {
        0%   { opacity: 0.9; transform: translate(-50%, -50%) scale(0.6); }
        70%  { opacity: 0.6; transform: translate(-50%, -50%) scale(2.2); }
        100% { opacity: 0.0; transform: translate(-50%, -50%) scale(2.8); }
      }
      """
    ))

    page.evaluate(
      """
      () => {
        if (document.getElementById('pw-cursor')) return;
        const cursor = document.createElement('div');
        cursor.id = 'pw-cursor';
        document.documentElement.appendChild(cursor);

        const move = (x, y) => {
          cursor.style.left = x + 'px';
          cursor.style.top  = y + 'px';
        };

        document.addEventListener('mousemove', (e) => move(e.clientX, e.clientY), true);
        document.addEventListener('pointermove', (e) => move(e.clientX, e.clientY), true);

        const clickRing = (x, y) => {
          const ring = document.createElement('div');
          ring.className = 'pw-click';
          ring.style.left = x + 'px';
          ring.style.top  = y + 'px';
          document.documentElement.appendChild(ring);
          setTimeout(() => ring.remove(), 650);
        };

        document.addEventListener('mousedown', (e) => { move(e.clientX, e.clientY); clickRing(e.clientX, e.clientY); }, true);
        document.addEventListener('pointerdown', (e) => { move(e.clientX, e.clientY); clickRing(e.clientX, e.clientY); }, true);
      }
      """
    )
  }

  def clickWithCursor(page: Page, loc: Locator, steps: Int = 20): Unit = {
    waitVisible(loc)
    val box = loc.boundingBox()
    if (box == null) throw new RuntimeException("No bounding box")
    val x = box.x + box.width / 2.0
    val y = box.y + box.height / 2.0
    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(steps))
    page.mouse().click(x, y)
  }

  def hoverWithCursor(page: Page, loc: Locator, steps: Int = 20): Unit = {
    waitVisible(loc)
    val box = loc.boundingBox()
    if (box == null) throw new RuntimeException("No bounding box")
    val x = box.x + box.width / 2.0
    val y = box.y + box.height / 2.0
    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(steps))
  }

  def chooseFirstDropdownOption(page: Page): Unit = {
    val dropdown = page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
    waitVisible(dropdown)
    val option = dropdown.locator(".ant-select-item-option:not(.ant-select-item-option-disabled)").first()
    waitVisible(option)
    clickWithCursor(page, option)
  }

  def chooseDropdownOptionByText(page: Page, text: String): Unit = {
    val target = text.trim
    val dropdown = page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
    waitVisible(dropdown)

    def exactOption(): Locator =
      dropdown.getByText(target, new Locator.GetByTextOptions().setExact(true)).first()

    def clickIfFound(option: Locator): Boolean = {
      if (option.count() == 0) return false
      try option.scrollIntoViewIfNeeded() catch { case _: Exception => }
      waitVisible(option)
      clickWithCursor(page, option)
      true
    }

    if (clickIfFound(exactOption())) return

    // Try dropdown search input first (if this select supports typing filter).
    val localSearch = dropdown
      .locator("input[type='search'], input[aria-autocomplete='list'], .ant-select-selection-search input")
      .first()
    if (localSearch.count() > 0) {
      try {
        clickWithCursor(page, localSearch)
        localSearch.fill("")
        localSearch.fill(target)
        page.waitForTimeout(150)
        if (clickIfFound(exactOption())) return
      } catch {
        case _: Exception =>
      }
    } else {
      val globalSearch = page.locator(".ant-select-selection-search input").last()
      if (globalSearch.count() > 0) {
        try {
          clickWithCursor(page, globalSearch)
          globalSearch.fill("")
          globalSearch.fill(target)
          page.waitForTimeout(150)
          if (clickIfFound(exactOption())) return
        } catch {
          case _: Exception =>
        }
      }
    }

    // Fallback for long virtualized lists: scroll in dropdown and retry exact match.
    try hoverWithCursor(page, dropdown, steps = 10) catch { case _: Exception => }
    var i = 0
    while (i < 24) {
      if (clickIfFound(exactOption())) return
      page.mouse().wheel(0, 320)
      page.waitForTimeout(100)
      i += 1
    }

    val fuzzyOption = dropdown.getByText(target, new Locator.GetByTextOptions().setExact(false)).first()
    if (clickIfFound(fuzzyOption)) return

    throw new RuntimeException(s"Dropdown option not found: $text")
  }

  // ── Shared geometry helpers ──

  def cellBox(cell: Locator): BoundingBox = {
    val body = cell.locator("rect.body").first()
    if (body.count() > 0) body.boundingBox() else cell.boundingBox()
  }

  def nudgeCell(page: Page, cell: Locator, dx: Double, dy: Double): Unit = {
    val body = cell.locator("rect.body").first()
    val box = if (body.count() > 0) body.boundingBox() else cell.boundingBox()
    if (box == null) return
    val startX = box.x + box.width / 2.0
    val startY = box.y + box.height / 2.0
    page.mouse().move(startX, startY, new Mouse.MoveOptions().setSteps(20))
    page.mouse().down()
    page.mouse().move(startX + dx, startY + dy, new Mouse.MoveOptions().setSteps(40))
    page.mouse().up()
  }

  def overlaps(a: BoundingBox, b: BoundingBox): Boolean = {
    val ax2 = a.x + a.width; val ay2 = a.y + a.height
    val bx2 = b.x + b.width; val by2 = b.y + b.height
    ax2 > b.x && bx2 > a.x && ay2 > b.y && by2 > a.y
  }

  def centerDx(a: BoundingBox, b: BoundingBox): Double = {
    val ax = a.x + a.width / 2.0
    val bx = b.x + b.width / 2.0
    math.abs(ax - bx)
  }

  def ensureSeparated(page: Page, source: Locator, target: Locator, minSpacing: Double = 140.0): Unit = {
    val srcBox = cellBox(source)
    val tgtBox = cellBox(target)
    if (srcBox == null || tgtBox == null) return

    if (overlaps(srcBox, tgtBox) || centerDx(srcBox, tgtBox) < minSpacing) {
      nudgeCell(page, target, dx = 260, dy = 0)
      page.waitForTimeout(200)
      val t1 = cellBox(target)
      if (t1 != null && (overlaps(srcBox, t1) || centerDx(srcBox, t1) < minSpacing)) {
        nudgeCell(page, target, dx = 360, dy = 120)
        page.waitForTimeout(200)
      }
    }
  }

}
