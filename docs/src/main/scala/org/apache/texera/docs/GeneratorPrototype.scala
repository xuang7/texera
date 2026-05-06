// prototype

package org.apache.texera.docs

import com.microsoft.playwright._
import com.microsoft.playwright.options.{AriaRole, LoadState, WaitForSelectorState}
import java.nio.file.{Files, Paths}

object GeneratorPrototype {

  /** Injects a visible “fake cursor” + click ripple into the page so recordings are easy to follow. */
  private def installFakeCursor(page: Page): Unit = {
    page.addStyleTag(new Page.AddStyleTagOptions().setContent(
      """
      #pw-cursor {
        position: fixed;
        left: 0;
        top: 0;
        width: 14px;
        height: 14px;
        border-radius: 50%;
        background: rgba(255, 0, 0, 0.9);
        box-shadow: 0 0 0 3px rgba(255, 0, 0, 0.25);
        pointer-events: none;
        z-index: 2147483647;
        transform: translate(-50%, -50%);
      }
      .pw-click {
        position: fixed;
        left: 0;
        top: 0;
        width: 18px;
        height: 18px;
        border-radius: 50%;
        border: 3px solid rgba(255, 0, 0, 0.85);
        pointer-events: none;
        z-index: 2147483647;
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

        // Track pointer/mouse movement
        document.addEventListener('mousemove', (e) => move(e.clientX, e.clientY), true);
        document.addEventListener('pointermove', (e) => move(e.clientX, e.clientY), true);

        // Click ripple
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

  /** Moves mouse to center of locator (visible in headed mode) and clicks at that point. */
  private def clickWithCursor(page: Page, loc: Locator, steps: Int = 20): Unit = {
    loc.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
    val box = loc.boundingBox()
    if (box == null) throw new RuntimeException("No bounding box for locator (not visible?)")

    val x = box.x + box.width / 2.0
    val y = box.y + box.height / 2.0

    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(steps))
    page.mouse().click(x, y)
  }

  /** Moves mouse to center of locator (useful for showing where we’re about to act). */
  private def hoverWithCursor(page: Page, loc: Locator, steps: Int = 20): Unit = {
    loc.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
    val box = loc.boundingBox()
    if (box == null) throw new RuntimeException("No bounding box for locator (not visible?)")

    val x = box.x + box.width / 2.0
    val y = box.y + box.height / 2.0
    page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(steps))
  }

  def main(args: Array[String]): Unit = {
    val videoDir = Paths.get("docs", "generated", "videos")
    Files.createDirectories(videoDir)

    val playwright = Playwright.create()
    val browser = playwright.chromium().launch(
      new BrowserType.LaunchOptions()
        .setHeadless(false)
        .setSlowMo(400) // slows down actions so humans can follow
    )

    val context = browser.newContext(
      new Browser.NewContextOptions()
        .setRecordVideoDir(videoDir)
        .setRecordVideoSize(1280, 720)
    )

    val page = context.newPage()

    try {
      // 1) Load localhost and wait until "ready"
      page.navigate("http://localhost:4200/")
      page.waitForLoadState(LoadState.NETWORKIDLE)

      // Install fake cursor AFTER initial navigation so it appears in the recording
      installFakeCursor(page)

      // Wait for login form fields to be visible (Angular "ready enough")
      val username = page.getByPlaceholder("Username")
      val password = page.getByPlaceholder("Password")
      username.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      password.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      // 2) Fill credentials (hover first so viewer sees focus target)
      hoverWithCursor(page, username)
      username.fill("texera")

      hoverWithCursor(page, password)
      password.fill("texera")

      // 3) Click "Sign in" (avoid Google button ambiguity by using the specific AntD button)
      val signInBtn = page.locator("button.login-form-button:has-text('Sign in')").first()
      clickWithCursor(page, signInBtn)

      // 4) Wait for redirect / post-login page to load
      page.waitForLoadState(LoadState.NETWORKIDLE)

      // 5) Click "Create Workflow"
      val createWorkflowBtn =
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create Workflow"))
      clickWithCursor(page, createWorkflowBtn)

      // 6) Wait for editor/canvas to appear
      val canvas = page.locator("svg[joint-selector='svg'], svg#v-2")
      canvas.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      // 7) Click the side menu item "Operators"
      val operatorsMenu =
        page.locator(
          "li[nz-menu-item]:has-text('Operators'), " +
            "li[nz-menu-item][title='Operators'], " +
            "li[nz-menu-item][ng-reflect-directive-title='Operators']"
        ).first()
      clickWithCursor(page, operatorsMenu)

      // 8) Expand "Data Input"
      val dataInputHeader = page.locator(".ant-collapse-header:has-text('Data Input')").first()
      clickWithCursor(page, dataInputHeader)

      // 9) Drag "CSV File Scan" onto the canvas (show cursor movement)
      val csvOp = page.locator(".operator-label:has-text('CSV File Scan')").first()
      csvOp.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      val srcBox = csvOp.boundingBox()
      val tgtBox = canvas.first().boundingBox()
      if (srcBox == null || tgtBox == null) throw new RuntimeException("Could not compute bounding boxes for drag-and-drop.")

      val srcX = srcBox.x + srcBox.width / 2.0
      val srcY = srcBox.y + srcBox.height / 2.0
      val tgtX = tgtBox.x + tgtBox.width / 2.0
      val tgtY = tgtBox.y + tgtBox.height / 2.0

      // Move to operator, drag to canvas
      page.mouse().move(srcX, srcY, new Mouse.MoveOptions().setSteps(25))
      page.mouse().down()
      page.mouse().move(tgtX, tgtY, new Mouse.MoveOptions().setSteps(35))
      page.mouse().up()

      // --- CSV File Scan: pick a file ---

      // 1) Click "Select File"
      val selectFileBtn = page.locator("button.file-select-button:has-text('Select File')").first()
      clickWithCursor(page, selectFileBtn)

      // Step 2 (correct): scope to the file-selection model + use Locator.pressSequentially()

      val fileSelection = page.locator("texera-file-selection-model").last()
      fileSelection.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      val datasetSelect = fileSelection.locator("nz-select.select-dataset, .ant-select.select-dataset").first()

      // Click the select box to ensure it is focused/open
      val selectBox = datasetSelect.locator(".ant-select-selector").first()
      clickWithCursor(page, selectBox)

      // Wait until it is open (you can see ant-select-open in your HTML)
      datasetSelect.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      page.locator(".cdk-overlay-container .ant-select-dropdown").first()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      // Now target the *correct* search input inside this select
      val searchInput = datasetSelect.locator("input.ant-select-selection-search-input").first()
      searchInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))

      hoverWithCursor(page, searchInput)
      searchInput.click()

      // Clear any previous text (important if the control keeps old value)
      searchInput.press("Control+A")
      searchInput.press("Backspace")

      // Type "test" using the recommended method for special keyboard handling
      searchInput.pressSequentially("test", new Locator.PressSequentiallyOptions().setDelay(80))

      // Press Enter (as you requested)
      searchInput.press("Enter")


      // 3) Click the file option "appointments.csv"
      val fileOption = page.locator("span[title='appointments.csv'], span:has-text('appointments.csv')").first()
      fileOption.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      clickWithCursor(page, fileOption)

      // Optional: let it settle for video
      page.waitForTimeout(2000)

      println(s"Done. Video saved under: ${videoDir.toAbsolutePath}")

    } finally {
      // IMPORTANT: closing the context finalizes the video file
      context.close()
      browser.close()
      playwright.close()
    }
  }
}
