package org.apache.texera.docs.controllers

import com.microsoft.playwright._
import com.microsoft.playwright.options.{AriaRole, LoadState, WaitForSelectorState}
import org.apache.texera.docs.config.TestDataConfig

// ═══════════════════════════════════════════════════════════════════
// Shared form-filling helpers
// ═══════════════════════════════════════════════════════════════════

private[controllers] object FormHelpers {

  def isFieldRequired(field: Locator): Boolean = {
    field.locator("label.ant-form-item-required").count() > 0 ||
      field.locator(".ant-form-item-required").count() > 0 ||
      field.locator("[aria-required='true']").count() > 0 ||
      field.locator("input[required], textarea[required], select[required]").count() > 0
  }

  def tryFillFieldContainer(page: Page, container: Locator, value: String): Boolean = {
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
      if (value.trim.nonEmpty) Utils.chooseDropdownOptionByText(page, value)
      else Utils.chooseFirstDropdownOption(page)
      return true
    }
    false
  }

  def tryFillSelect(page: Page, field: Locator, value: Option[String] = None): Boolean = {
    val select = field.locator("nz-select").first()
    if (select.count() == 0) return false

    if (value.isEmpty) {
      val placeholder = field.locator(".ant-select-selection-placeholder")
      val selected = field.locator(".ant-select-selection-item")
      if (placeholder.count() == 0 && selected.count() > 0) return true
    }

    val selector = select.locator(".ant-select-selector").first()
    if (selector.count() == 0) return false

    Utils.clickWithCursor(page, selector)
    value.filter(_.trim.nonEmpty) match {
      case Some(v) => Utils.chooseDropdownOptionByText(page, v)
      case _       => Utils.chooseFirstDropdownOption(page)
    }
    true
  }

  def tryFillText(page: Page, field: Locator, value: String): Boolean = {
    val input = field.locator("textarea, input:not([type='checkbox']):not([type='radio'])").first()
    if (input.count() == 0) return false

    val current = try input.inputValue() catch { case _: Exception => "" }
    if (current != null && current.nonEmpty) return true

    Utils.clickWithCursor(page, input)
    input.fill(value)
    true
  }

  def tryCheckCheckbox(page: Page, field: Locator): Boolean = {
    val checkbox = field.locator("input[type='checkbox']").first()
    if (checkbox.count() == 0) return false
    val isChecked = try checkbox.isChecked() catch { case _: Exception => false }
    if (!isChecked) Utils.clickWithCursor(page, checkbox)
    true
  }
}

// ═══════════════════════════════════════════════════════════════════
// 1. LoginControllerBuilder
//    new LoginControllerBuilder(ctx).login("user","pass").execute()
//    new LoginControllerBuilder(ctx).login("u","p").logout().execute()
// ═══════════════════════════════════════════════════════════════════

class LoginControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[LoginControllerBuilder] {

  override protected def self: LoginControllerBuilder = this

  def login(username: String, password: String): LoginControllerBuilder = addStep(new ControllerStep {
    override def name = "Login"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      println(s"[Login] Navigating to ${TestDataConfig.baseUrl}")
      page.navigate(TestDataConfig.baseUrl)
      page.waitForLoadState(LoadState.NETWORKIDLE)
      page.waitForTimeout(400)
      ctx.ensureFakeCursor()

      val loginSubmit = page.getByTestId("login-submit")
      println(s"[Login] login-submit count = ${loginSubmit.count()}, url = ${page.url()}")
      if (loginSubmit.count() == 0) {
        val loggedIn = page.url().contains("/dashboard") || page.url().contains("/workflow")
        if (loggedIn) return
        try {
          loginSubmit.first().waitFor(
            new Locator.WaitForOptions()
              .setState(WaitForSelectorState.VISIBLE)
              .setTimeout(5000)
          )
        } catch {
          case _: Exception =>
            throw new RuntimeException(s"Login page not visible. URL: ${page.url()}")
        }
      }

      // Ensure Sign In tab is active if present
      val signInTab = page.getByText("Sign In", new Page.GetByTextOptions().setExact(true)).first()
      if (signInTab.count() > 0 && signInTab.isVisible()) {
        Utils.clickWithCursor(page, signInTab)
        page.waitForTimeout(200)
      }

      val usernameField = page.getByTestId("login-username")
        .or(page.getByPlaceholder("Username")).first()
      usernameField.waitFor(
        new Locator.WaitForOptions()
          .setState(WaitForSelectorState.VISIBLE)
          .setTimeout(5000)
      )
      Utils.clickWithCursor(page, usernameField)
      usernameField.fill(username)
      val usernameVal = try usernameField.inputValue() catch { case _: Exception => "" }
      if (usernameVal != username) {
        usernameField.click(new Locator.ClickOptions().setForce(true))
        usernameField.type(username, new Locator.TypeOptions().setDelay(30))
      }

      val passwordField = page.getByTestId("login-password")
        .or(page.getByPlaceholder("Password")).first()
      passwordField.waitFor(
        new Locator.WaitForOptions()
          .setState(WaitForSelectorState.VISIBLE)
          .setTimeout(5000)
      )
      Utils.clickWithCursor(page, passwordField)
      passwordField.fill(password)
      val passwordVal = try passwordField.inputValue() catch { case _: Exception => "" }
      if (passwordVal != password) {
        passwordField.click(new Locator.ClickOptions().setForce(true))
        passwordField.type(password, new Locator.TypeOptions().setDelay(30))
      }

      val signInBtn = page.getByTestId("login-submit")
        .or(page.locator("button.login-form-button:has-text('Sign in')")).first()
      Utils.waitVisible(signInBtn)
      Utils.clickWithCursor(page, signInBtn)
      page.waitForLoadState(LoadState.NETWORKIDLE)
      page.waitForTimeout(500)

      // If still on login page, try pressing Enter once.
      if (page.getByTestId("login-submit").count() > 0) {
        passwordField.press("Enter")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.waitForTimeout(500)
      }

      // If still not logged in, surface the error (if any) for debugging.
      if (page.getByTestId("login-submit").count() > 0) {
        val err = page.locator("form.login-form p").first()
        val msg = if (err.count() > 0) err.innerText() else "Login still visible"
        throw new RuntimeException(s"Login failed: $msg")
      }
    }
  })

  def logout(): LoginControllerBuilder = addStep(new ControllerStep {
    override def name = "Logout"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val userMenu = page.getByTestId("user-menu-button")
        .or(page.locator(".user-icon, .avatar")).first()
      if (userMenu.count() > 0) {
        Utils.clickWithCursor(page, userMenu)
        page.waitForTimeout(300)
      }

      val logoutBtn = page.getByTestId("logout-button")
        .or(page.getByText("Log Out"))
        .or(page.getByText("Sign out")).first()
      if (logoutBtn.count() > 0) {
        Utils.clickWithCursor(page, logoutBtn)
        page.waitForLoadState(LoadState.NETWORKIDLE)
      }
    }
  })
}

// ═══════════════════════════════════════════════════════════════════
// 2. NavigationControllerBuilder
//    new NavigationControllerBuilder(ctx).openWorkflow(id, name).cleanWorkflow().execute()
// ═══════════════════════════════════════════════════════════════════

class NavigationControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[NavigationControllerBuilder] {

  override protected def self: NavigationControllerBuilder = this

  def openWorkflow(workflowId: String, workflowName: String = ""): NavigationControllerBuilder = addStep(new ControllerStep {
    override def name = s"Navigate to ${if (workflowName.nonEmpty) workflowName else workflowId}"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      page.navigate(s"${TestDataConfig.baseUrl}/dashboard/user/workflow/$workflowId")
      page.waitForLoadState(LoadState.NETWORKIDLE)
      ctx.ensureFakeCursor()
      Utils.waitVisible(page.getByTestId("workflow-canvas").first())
    }
  })

  def cleanWorkflow(): NavigationControllerBuilder = addStep(new ControllerStep {
    override def name = "Clean Workflow"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val deleteAll = page.getByTitle("delete all").first()
        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("delete all")).first())
      if (deleteAll.count() > 0) {
        Utils.clickWithCursor(page, deleteAll)
        page.waitForTimeout(300)
      }
    }
  })
}

// ═══════════════════════════════════════════════════════════════════
// 3. OperatorControllerBuilder
//    new OperatorControllerBuilder(ctx)
//      .insertViaSearch("CSV File Scan")
//      .insertViaSearch("Regex")
//      .connectLastTwo()
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class OperatorControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[OperatorControllerBuilder] {

  override protected def self: OperatorControllerBuilder = this

  def insertViaSearch(operatorName: String): OperatorControllerBuilder = addStep(new ControllerStep {
    override def name = s"Insert '$operatorName' via Search"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val operatorsMenu = page.getByTestId("left-panel-operators-button")
        .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(operatorsMenu)
      Utils.clickWithCursor(page, operatorsMenu)

      val searchInput = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      Utils.waitVisible(searchInput)
      Utils.clickWithCursor(page, searchInput)
      searchInput.fill(operatorName)

      val beforeCount = page.locator("g.joint-cell.joint-element").count()
      searchInput.press("Enter")

      val targetCount = beforeCount + 1
      var retries = 0
      while (page.locator("g.joint-cell.joint-element").count() < targetCount && retries < 20) {
        page.waitForTimeout(150)
        retries += 1
      }

      val newNode = Utils.waitVisible(page.locator("g.joint-cell.joint-element").nth(beforeCount))
      if (newNode.count() > 0) {
        val body = newNode.locator("rect.body").first()
        if (body.count() > 0) Utils.clickWithCursor(page, body) else Utils.clickWithCursor(page, newNode)
      }

      if (beforeCount > 0) {
        val prevNode = page.locator("g.joint-cell.joint-element").nth(beforeCount - 1)
        repositionNodeNear(page, prevNode, newNode, spacing = 220)
        val body = newNode.locator("rect.body").first()
        if (body.count() > 0) Utils.clickWithCursor(page, body) else Utils.clickWithCursor(page, newNode)
      }

      try {
        page.getByTestId("property-panel-title").first()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(2000))
      } catch { case _: Exception => }
    }

    private def repositionNodeNear(page: Page, prev: Locator, cur: Locator, spacing: Double): Unit = {
      if (prev.count() == 0 || cur.count() == 0) return
      val prevBox = Utils.cellBox(prev)
      val curBox = Utils.cellBox(cur)
      if (prevBox == null || curBox == null) return

      val targetCenterX = prevBox.x + prevBox.width + spacing + curBox.width / 2.0
      val dx = targetCenterX - (curBox.x + curBox.width / 2.0)

      Utils.nudgeCell(page, cur, dx, 0)
      page.waitForTimeout(250)

      val afterBox = Utils.cellBox(cur)
      if (afterBox != null && (Utils.overlaps(prevBox, afterBox) || Utils.centerDx(prevBox, afterBox) < spacing * 0.8)) {
        Utils.nudgeCell(page, cur, dx + spacing, 0)
        page.waitForTimeout(250)
      }
    }
  })

  def insertViaDrag(
                     operatorName: String,
                     operatorType: Option[String] = None,
                     canvasPosition: (Double, Double) = (0.3, 0.35)
                   ): OperatorControllerBuilder = addStep(new ControllerStep {
    override def name = s"Insert '$operatorName' via Drag"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val operatorsMenu = page.getByTestId("left-panel-operators-button")
        .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(operatorsMenu)
      Utils.clickWithCursor(page, operatorsMenu)

      val searchInput = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      Utils.waitVisible(searchInput)
      Utils.clickWithCursor(page, searchInput)
      searchInput.fill(operatorName)

      val operatorByType = operatorType.filter(_.nonEmpty)
        .map(t => page.getByTestId(s"operator-item-$t").first())
      val operator = operatorByType match {
        case Some(loc) if loc.count() > 0 => loc
        case _ =>
          val leftPanel = page.locator("#left-container")
          var loc = leftPanel.locator(s".operator-label:has-text('$operatorName')").first()
          if (loc.count() == 0) loc = page.locator(s".operator-label:has-text('$operatorName')").first()
          loc
      }
      Utils.waitVisible(operator)
      operator.scrollIntoViewIfNeeded()
      page.waitForTimeout(150)

      val canvas = page.getByTestId("workflow-canvas")
        .or(page.locator("svg[joint-selector='svg'], svg#v-2")).first()
      Utils.waitVisible(canvas)
      canvas.scrollIntoViewIfNeeded()
      page.waitForTimeout(100)

      val beforeCount = page.locator("g.joint-cell.joint-element").count()
      val srcBox = operator.boundingBox()
      val canvasBox = canvas.boundingBox()
      if (srcBox == null || canvasBox == null) throw new RuntimeException("Drag failed: missing bounding boxes")

      val srcX = srcBox.x + srcBox.width / 2.0
      val srcY = srcBox.y + srcBox.height / 2.0
      val index = Math.max(0, beforeCount)
      val baseX = canvasBox.x + canvasBox.width * canvasPosition._1
      val baseY = canvasBox.y + canvasBox.height * canvasPosition._2
      val tgtX = math.min(canvasBox.x + canvasBox.width - 40, baseX + (index % 4) * 180)
      val tgtY = math.min(canvasBox.y + canvasBox.height - 40, baseY + (index / 4) * 120)

      page.mouse().move(srcX, srcY, new Mouse.MoveOptions().setSteps(25))
      page.mouse().down()
      page.mouse().move(tgtX, tgtY, new Mouse.MoveOptions().setSteps(35))
      page.mouse().up()

      val targetCount = beforeCount + 1
      var retries = 0
      while (page.locator("g.joint-cell.joint-element").count() < targetCount && retries < 20) {
        page.waitForTimeout(150)
        retries += 1
      }

      val newNode = Utils.waitVisible(page.locator("g.joint-cell.joint-element").nth(beforeCount))
      if (newNode.count() > 0) {
        val body = newNode.locator("rect.body").first()
        if (body.count() > 0) Utils.clickWithCursor(page, body) else Utils.clickWithCursor(page, newNode)
      }

      if (beforeCount > 0) {
        val prevNode = page.locator("g.joint-cell.joint-element").nth(beforeCount - 1)
        Utils.ensureSeparated(page, prevNode, newNode)
      }
    }
  })

  def connectLastTwo(): OperatorControllerBuilder = addStep(new ControllerStep {
    override def name = "Connect Last Two Operators"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val cells = page.locator("g.joint-cell.joint-element")
      val count = cells.count()
      if (count < 2) return

      val source = cells.nth(count - 2)
      val target = cells.nth(count - 1)

      val beforeLinks = page.locator("g.joint-link").count()

      def boxOf(loc: Locator): com.microsoft.playwright.options.BoundingBox = {
        if (loc == null || loc.count() == 0) null else loc.boundingBox()
      }

      val srcPort = Option(Utils.findPort(source, "out")).filter(_.count() > 0)
        .orElse(Option(Utils.findPort(source, "output")).filter(_.count() > 0))
      val tgtPort = Option(Utils.findPort(target, "in")).filter(_.count() > 0)
        .orElse(Option(Utils.findPort(target, "input")).filter(_.count() > 0))

      val srcBox = srcPort.flatMap(p => Option(boxOf(p))).getOrElse(Utils.cellBox(source))
      val tgtBox = tgtPort.flatMap(p => Option(boxOf(p))).getOrElse(Utils.cellBox(target))
      if (srcBox == null || tgtBox == null) return

      val srcX = if (srcPort.isDefined) srcBox.x + srcBox.width / 2.0 else srcBox.x + srcBox.width - 2.0
      val srcY = srcBox.y + srcBox.height / 2.0
      val tgtX = if (tgtPort.isDefined) tgtBox.x + tgtBox.width / 2.0 else tgtBox.x + 2.0
      val tgtY = tgtBox.y + tgtBox.height / 2.0

      page.mouse().move(srcX, srcY)
      page.waitForTimeout(120)
      page.mouse().down()
      page.waitForTimeout(120)
      page.mouse().move(tgtX, tgtY, new Mouse.MoveOptions().setSteps(25))
      page.waitForTimeout(120)
      page.mouse().up()
      page.waitForTimeout(400)

      val afterLinks = page.locator("g.joint-link").count()
      if (afterLinks <= beforeLinks) {
        val fallbackSrc = Utils.findPort(source, "out")
        val fallbackTgt = Utils.findPort(target, "in")
        if (fallbackSrc != null && fallbackTgt != null) {
          val fbSrc = fallbackSrc.boundingBox()
          val fbTgt = fallbackTgt.boundingBox()
          if (fbSrc != null && fbTgt != null) {
            page.mouse().move(fbSrc.x + fbSrc.width / 2.0, fbSrc.y + fbSrc.height / 2.0)
            page.mouse().down()
            page.mouse().move(fbTgt.x + fbTgt.width / 2.0, fbTgt.y + fbTgt.height / 2.0, new Mouse.MoveOptions().setSteps(20))
            page.mouse().up()
            page.waitForTimeout(300)
          }
        }
      }
    }
  })
}

// ═══════════════════════════════════════════════════════════════════
// 4. PropertyPanelControllerBuilder
//    new PropertyPanelControllerBuilder(ctx).resize(400).execute()
// ═══════════════════════════════════════════════════════════════════

class PropertyPanelControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[PropertyPanelControllerBuilder] {

  override protected def self: PropertyPanelControllerBuilder = this

  def resize(height: Double = TestDataConfig.uiConfig.propertyPanelResizeHeight): PropertyPanelControllerBuilder = addStep(new ControllerStep {
    override def name = "Resize Property Panel"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val container = page.getByTestId("property-panel").first()
      Utils.waitVisible(container)
      val containerBox = container.boundingBox()
      if (containerBox == null) return

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
  })
}

// ═══════════════════════════════════════════════════════════════════
// 5. DatasetControllerBuilder
//    new DatasetControllerBuilder(ctx)
//      .datasetName("my-dataset")
//      .datasetVersion("v1")
//      .file("data.csv")
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class DatasetControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[DatasetControllerBuilder] {

  override protected def self: DatasetControllerBuilder = this

  private var _datasetName: String = _
  private var _versionName: String = _
  private var _fileName: Option[String] = None

  def datasetName(name: String): DatasetControllerBuilder = { _datasetName = name; self }
  def datasetVersion(version: String): DatasetControllerBuilder = { _versionName = version; self }
  def file(name: String): DatasetControllerBuilder = { _fileName = Some(name); self }

  /** Convenience: load dataset + version from TestDataConfig by key. */
  def fromConfig(datasetKey: String): DatasetControllerBuilder = {
    val ds = TestDataConfig.datasets(datasetKey)
    _datasetName = ds.name
    _versionName = ds.version
    self
  }

  override def build(): Controller = {
    require(_datasetName != null && _datasetName.nonEmpty, "datasetName is required")
    require(_versionName != null && _versionName.nonEmpty, "datasetVersion is required")
    addStep(new FileSelectionStep(_datasetName, _versionName, _fileName))
    super.build()
  }

  private class FileSelectionStep(datasetName: String, versionName: String, fileName: Option[String]) extends ControllerStep {
    override def name = s"Select File from $datasetName/$versionName"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page

      val selectBtn = page.getByTestId("file-selection-open").first()
      Utils.waitVisible(selectBtn)
      Utils.clickWithCursor(page, selectBtn)

      val modal = page.getByTestId("file-selection-modal")
      Utils.waitVisible(modal)

      val datasetSelect = modal.getByTestId("file-selection-dataset").first()
      val datasetBox = datasetSelect.locator(".ant-select-selector").first()
      Utils.clickWithCursor(page, datasetBox)
      page.locator(".cdk-overlay-container .ant-select-dropdown").first()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      Utils.chooseDropdownOptionByText(page, datasetName)

      val versionSelect = modal.getByTestId("file-selection-version").first()
      if (versionSelect.count() > 0) {
        val versionBox = versionSelect.locator(".ant-select-selector").first()
        Utils.clickWithCursor(page, versionBox)
        page.locator(".cdk-overlay-container .ant-select-dropdown").first()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        Utils.chooseDropdownOptionByText(page, versionName)
      }

      val fileTree = modal.getByTestId("file-selection-filetree").first()
      Utils.waitVisible(fileTree)
      val fileNode = fileName match {
        case Some(fn) =>
          val exact = fileTree.getByText(fn, new Locator.GetByTextOptions().setExact(true)).first()
          if (exact.count() > 0) exact else fileTree.getByText(fn).first()
        case None =>
          modal.locator("span[title*='.csv'], .ant-tree-node-content-wrapper").first()
      }
      if (fileNode.count() > 0) {
        Utils.waitVisible(fileNode)
        Utils.clickWithCursor(page, fileNode)
      }
    }
  }
}

// ═══════════════════════════════════════════════════════════════════
// 6. FormControllerBuilder
//    new FormControllerBuilder(ctx)
//      .fillField("fieldName", "value")
//      .fillFieldValues(Map("a" -> "1", "b" -> "2"))
//      .autoFillRequired()
//      .autoFillFields(Seq("key1", "key2"))
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class FormControllerBuilder(protected val context: ControllerContext)
  extends ControllerBuilder[FormControllerBuilder] {

  override protected def self: FormControllerBuilder = this

  def fillField(fieldName: String, value: String): FormControllerBuilder = addStep(new ControllerStep {
    override def name = s"Fill Field '$fieldName'"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      if (FormHelpers.tryFillFieldContainer(page, page.getByTestId(s"form-field-$fieldName"), value)) return

      val label = page.getByText(fieldName, new Page.GetByTextOptions().setExact(true)).first()
      Utils.waitVisible(label)
      val fieldContainer = label.locator("xpath=ancestor::formly-field[1]")
      if (FormHelpers.tryFillFieldContainer(page, fieldContainer, value)) return

      throw new RuntimeException(s"No supported input found for field: $fieldName")
    }
  })

  def fillFieldValues(values: Map[String, String]): FormControllerBuilder = addStep(new ControllerStep {
    override def name = s"Fill ${values.size} Field Values"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      values.foreach { case (fieldKey, value) =>
        val field = page.getByTestId(s"form-field-$fieldKey").first()
        if (field.count() == 0) {
          println(s"  Field not found: $fieldKey")
        } else if (!FormHelpers.tryFillSelect(page, field, Some(value)) &&
          !FormHelpers.tryFillText(page, field, value)) {
          println(s"  No supported input for field: $fieldKey")
        }
      }
    }
  })

  def autoFillRequired(maxFields: Int = 12, defaultText: String = "test"): FormControllerBuilder = addStep(new ControllerStep {
    override def name = "Auto Fill Required Fields"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val fields = page.locator("[data-testid^='form-field-']")
      val limit = Math.min(fields.count(), maxFields)

      for (i <- 0 until limit) {
        val field = fields.nth(i)
        if (FormHelpers.isFieldRequired(field)) {
          if (!FormHelpers.tryFillSelect(page, field) &&
            !FormHelpers.tryFillText(page, field, defaultText) &&
            !FormHelpers.tryCheckCheckbox(page, field)) {
            // skip unsupported
          }
        }
      }
    }
  })

  def autoFillFields(fieldKeys: Seq[String], defaultText: String = "test"): FormControllerBuilder = addStep(new ControllerStep {
    override def name = s"Auto Fill ${fieldKeys.size} Fields"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      fieldKeys.foreach { key =>
        val field = page.getByTestId(s"form-field-$key").first()
        if (field.count() == 0) {
          println(s"  Field not found: $key")
        } else if (!FormHelpers.tryFillSelect(page, field) &&
          !FormHelpers.tryFillText(page, field, defaultText) &&
          !FormHelpers.tryCheckCheckbox(page, field)) {
          println(s"  No supported input for field: $key")
        }
      }
    }
  })
}
