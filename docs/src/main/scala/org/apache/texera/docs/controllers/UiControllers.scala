// controllers/Controllers.scala
package org.apache.texera.docs.controllers

import com.microsoft.playwright._
import com.microsoft.playwright.options.{LoadState, WaitForSelectorState}
import org.apache.texera.docs.config.TestDataConfig

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
    val dropdown = page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
    waitVisible(dropdown)

    val exactOption = dropdown.getByText(text, new Locator.GetByTextOptions().setExact(true)).first()
    if (exactOption.count() > 0) {
      waitVisible(exactOption)
      clickWithCursor(page, exactOption)
      return
    }

    val fuzzyOption = dropdown.getByText(text, new Locator.GetByTextOptions().setExact(false)).first()
    if (fuzzyOption.count() == 0) {
      throw new RuntimeException(s"Dropdown option not found: $text")
    }
    waitVisible(fuzzyOption)
    clickWithCursor(page, fuzzyOption)
  }
}

// ============ Controller 1: Login ============
class LoginController(username: String, password: String) extends UiController {
  override def execute(page: Page): Unit = {
    println(s"[Login] Logging in as $username...")

    page.navigate(TestDataConfig.baseUrl)
    page.waitForLoadState(LoadState.NETWORKIDLE)
    Utils.installFakeCursor(page)

    val usernameField = page.getByTestId("login-username")
      .or(page.getByPlaceholder("Username"))
      .first()
    Utils.waitVisible(usernameField)
    Utils.hoverWithCursor(page, usernameField)
    usernameField.fill(username)

    val passwordField = page.getByTestId("login-password")
      .or(page.getByPlaceholder("Password"))
      .first()
    Utils.hoverWithCursor(page, passwordField)
    passwordField.fill(password)

    val signInBtn = page.getByTestId("login-submit")
      .or(page.locator("button.login-form-button:has-text('Sign in')"))
      .first()
    Utils.clickWithCursor(page, signInBtn)
    page.waitForLoadState(LoadState.NETWORKIDLE)

    println(s"[Login] Success")
  }
}

// ============ Controller 2: Navigation ============
class NavigationController(workflowId: String, workflowName: String) extends UiController {
  override def execute(page: Page): Unit = {
    println(s"[Navigation] Opening $workflowName...")

    page.navigate(s"${TestDataConfig.baseUrl}/dashboard/user/workflow/$workflowId")
    page.waitForLoadState(LoadState.NETWORKIDLE)
    Utils.installFakeCursor(page)

    val canvas = page.getByTestId("workflow-canvas")
    Utils.waitVisible(canvas.first())

    println(s"[Navigation] Workflow loaded")
  }
}

// ============ Controller 3: Operator Insert (Search + Enter) ============
class OperatorInsertViaSearch(
                               operatorName: String
                             ) extends UiController {

  override def execute(page: Page): Unit = {
    println(s"[Operator Add] Searching $operatorName and pressing Enter...")

    Utils.installFakeCursor(page)

    // 1) Open Operators menu
    val operatorsMenu = page.getByTestId("left-panel-operators-button")
      .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true)))
      .first()

    Utils.waitVisible(operatorsMenu)
    Utils.clickWithCursor(page, operatorsMenu)

    // 2) Focus search box and press Enter to add operator
    val searchInput = page.getByTestId("operator-search-input")
      .or(page.getByPlaceholder("search operator"))
      .first()

    Utils.waitVisible(searchInput)
    Utils.clickWithCursor(page, searchInput)
    searchInput.fill(operatorName)

    searchInput.press("Enter")

    // 3) Ensure node created + SELECT it
    val newNode = Utils.waitVisible(page.locator("g.joint-cell").last())

    if (newNode.count() > 0) {
      Utils.clickWithCursor(page, newNode)
    }

    // 4) Wait for property panel to be present
    Utils.waitVisible(page.getByTestId("property-panel-title").first())

    println(s"[Operator Add] $operatorName added & selected")
  }
}


// ============ Controller 4: Property Panel ============
class PropertyPanelController(
                               resizeHeight: Option[Double] = Some(TestDataConfig.uiConfig.propertyPanelResizeHeight)
                             ) extends UiController {
  override def execute(page: Page): Unit = {
    println("[Property Panel] Adjusting panel...")
    Utils.waitVisible(page.getByTestId("property-panel").first())

    // Resize height (drag to near bottom of viewport)
    resizeHeight.foreach { height =>
      val container = page.getByTestId("property-panel").first()

      if (container.count() > 0) {
        val containerBox = container.boundingBox()

        if (containerBox != null) {
          // Drag from the bottom edge of the panel to avoid picking the wrong resize handle.
          val x = containerBox.x + containerBox.width / 2.0
          val y = containerBox.y + containerBox.height - 2.0
          val viewport = page.viewportSize()
          val targetY = if (viewport != null) math.max(y + height, viewport.height - 20) else y + height

          page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(15))
          page.waitForTimeout(300)
          page.mouse().down()
          page.mouse().move(x, targetY, new Mouse.MoveOptions().setSteps(30))
          page.mouse().up()
          page.waitForTimeout(800)
        }
      }
    }

    println("[Property Panel] Adjusted")
  }
}


// ============ Controller 5: File Selection ============
class FileSelectionController(
                               datasetName: String,
                               versionName: String
                             ) extends UiController {
  override def execute(page: Page): Unit = {
    println(s"[File Selection] Selecting from $datasetName/$versionName...")

    val selectBtn = page.getByTestId("file-selection-open").first()
    Utils.waitVisible(selectBtn)
    Utils.clickWithCursor(page, selectBtn)

    val modal = page.getByTestId("file-selection-modal")
    Utils.waitVisible(modal)

    // Select dataset
    val datasetSelect = modal.getByTestId("file-selection-dataset").first()
    val datasetBox = datasetSelect.locator(".ant-select-selector").first()
    Utils.clickWithCursor(page, datasetBox)
    page.locator(".cdk-overlay-container .ant-select-dropdown").first()
      .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
    Utils.chooseDropdownOptionByText(page, datasetName)

    // Select version
    val versionSelect = modal.getByTestId("file-selection-version").first()
    if (versionSelect.count() > 0) {
      val versionBox = versionSelect.locator(".ant-select-selector").first()
      Utils.clickWithCursor(page, versionBox)
      page.locator(".cdk-overlay-container .ant-select-dropdown").first()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      Utils.chooseDropdownOptionByText(page, versionName)
    }

    // Click file
    val fileTree = modal.getByTestId("file-selection-filetree").first()
    Utils.waitVisible(fileTree)
    val fileNode = modal.locator("span[title*='.csv'], .ant-tree-node-content-wrapper").first()
    if (fileNode.count() > 0) {
      Utils.waitVisible(fileNode)
      Utils.clickWithCursor(page, fileNode)
    }

    println("[File Selection] File selected")
  }

  // Todo: replace CSS-based selectors (AntD dropdown rendering)
}

// ============ Controller 6: Field Config (for future use) ============
class FieldConfigController(
                             fieldName: String,
                             value: String
                           ) extends UiController {
  override def execute(page: Page): Unit = {
    println(s"[Field Config] Setting $fieldName = $value...")
    if (tryFillFieldContainer(page, page.getByTestId(s"form-field-$fieldName"), value)) return

    val label = page.getByText(fieldName, new Page.GetByTextOptions().setExact(true)).first()

    Utils.waitVisible(label)
    val fieldContainer = label.locator("xpath=ancestor::formly-field[1]")

    if (tryFillFieldContainer(page, fieldContainer, value)) return

    throw new RuntimeException(s"No supported input found for field: $fieldName")
  }

  private def tryFillFieldContainer(page: Page, container: Locator, value: String): Boolean = {
    if (container.count() == 0) return false

    val textInput = container.locator(
      "xpath=.//textarea | .//input[not(@type='checkbox') and not(@type='radio')]"
    ).first()

    if (textInput.count() > 0) {
      Utils.clickWithCursor(page, textInput)
      textInput.fill(value)
      return true
    }

    val select = container.locator("xpath=.//nz-select").first()

    if (select.count() > 0) {
      Utils.clickWithCursor(page, select)
      if (value.trim.nonEmpty) {
        Utils.chooseDropdownOptionByText(page, value)
      } else {
        Utils.chooseFirstDropdownOption(page)
      }
      return true
    }

    false
  }
}
