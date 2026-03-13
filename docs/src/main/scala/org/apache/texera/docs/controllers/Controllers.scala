package org.apache.texera.docs.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.microsoft.playwright._
import com.microsoft.playwright.options.{AriaRole, LoadState, WaitForSelectorState, WaitUntilState}
import org.apache.texera.amber.operator.metadata.{GroupInfo, OperatorGroupConstants, OperatorMetadataGenerator}
import org.apache.texera.docs.config.TestDataConfig
import scala.jdk.CollectionConverters._

// ═══════════════════════════════════════════════════════════════════
// Shared form-filling helpers
// ═══════════════════════════════════════════════════════════════════

private[controllers] object FormHelpers {

  private[controllers] def firstVisible(locator: Locator): Option[Locator] = {
    val count = locator.count()
    var i = 0
    while (i < count) {
      val nth = locator.nth(i)
      try {
        if (nth.isVisible()) return Some(nth)
      } catch {
        case _: Exception =>
      }
      i += 1
    }
    None
  }

  private def fieldScope(field: Locator): Locator = {
    val formly = field.locator("xpath=ancestor-or-self::formly-field[1]").first()
    if (formly.count() > 0) return formly

    val formItem = field.locator("xpath=ancestor-or-self::*[contains(@class,'ant-form-item')][1]").first()
    if (formItem.count() > 0) return formItem

    field
  }

  // Find the interactive element
  def focusField(page: Page, field: Locator): Unit = {
    val scope = fieldScope(field)
    try scope.scrollIntoViewIfNeeded() catch { case _: Exception => }
    val clickable = firstVisible(
      scope.locator(".ant-select-selector, input:not([type='checkbox']):not([type='radio']), textarea, [role='combobox']")
    ).getOrElse(scope)
    try Utils.clickWithCursor(page, clickable) catch { case _: Exception => }
  }

  def isBooleanLikeField(field: Locator): Boolean = {
    val scope = fieldScope(field)
    scope.locator("input[type='checkbox'], .ant-switch, button[role='switch']").count() > 0
  }

  def isFieldRequired(field: Locator): Boolean = {
    // Find the required field in property panel CSS.
    field.locator("label.ant-form-item-required").count() > 0 ||
      field.locator(".ant-form-item-required").count() > 0 ||
      field.locator("[aria-required='true']").count() > 0 ||
      field.locator("input[required], textarea[required], select[required]").count() > 0
  }

  def tryFillFieldContainer(page: Page, container: Locator, value: String): Boolean = {
    if (container.count() == 0) return false

    // First fill in plain text
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
    val scope = fieldScope(field)
    val selector = Seq(".ant-select-selector", ".ant-select", "[role='combobox']", "nz-select")
      .view
      .flatMap { q =>
        firstVisible(scope.locator(q)).orElse(firstVisible(field.locator(q)))
      }
      .headOption
      .orNull
    if (selector == null || selector.count() == 0) return false

    if (value.isEmpty) {
      val placeholder = scope.locator(".ant-select-selection-placeholder")
      val selected = scope.locator(".ant-select-selection-item")
      if (placeholder.count() == 0 && selected.count() > 0) return true
    }

    // Close any stale dropdown first, otherwise selection can be applied to the wrong field.
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() > 0) {
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(100)
    }

    try selector.scrollIntoViewIfNeeded() catch { case _: Exception => }
    Utils.clickWithCursor(page, selector)

    // retry limit: 8
    var retries = 0
    while (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0 && retries < 8) {
      page.waitForTimeout(100)
      retries += 1
    }
    // Some selects need one more click to open.
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0) {
      Utils.clickWithCursor(page, selector)
      retries = 0
      while (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0 && retries < 8) {
        page.waitForTimeout(100)
        retries += 1
      }
    }

    value.filter(_.trim.nonEmpty) match {
      case Some(v) =>
        try {
          Utils.chooseDropdownOptionByText(page, v)
        } catch {
          case _: Exception =>
            // Configured value may belong to another dataset/schema.
            // Fallback to first visible option so script can keep progressing.
            println(s"  [WARN] Dropdown value not found: '$v', fallback to first option")
            Utils.chooseFirstDropdownOption(page)
        }
      case _       => Utils.chooseFirstDropdownOption(page)
    }

    // Ensure dropdown is closed before moving to the next field.
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() > 0) {
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(80)
    }
    true
  }

  def tryFillSelectMany(page: Page, field: Locator, values: Seq[String]): Boolean = {
    val clean = values.map(_.trim).filter(_.nonEmpty)
    if (clean.isEmpty) return true
    clean.foreach { v =>
      if (!tryFillSelect(page, field, Some(v))) return false
      page.waitForTimeout(80)
    }

    val scope = fieldScope(field)
    def norm(s: String): String = s.trim.toLowerCase
    def selectedNow: Set[String] = {
      val items = scope.locator(".ant-select-selection-item")
      val n = items.count()
      var i = 0
      var acc = Set.empty[String]
      while (i < n) {
        val txt = try items.nth(i).innerText() catch { case _: Exception => "" }
        if (txt != null && txt.trim.nonEmpty) acc += norm(txt)
        i += 1
      }
      acc
    }

    var selected = selectedNow
    val missing1 = clean.filterNot(v => selected.contains(norm(v)))
    if (missing1.nonEmpty) {
      missing1.foreach { v =>
        if (!tryFillSelect(page, field, Some(v))) return false
        page.waitForTimeout(80)
      }
      selected = selectedNow
    }

    clean.forall(v => selected.contains(norm(v)))
  }

  // Enter given value
  def tryFillText(page: Page, field: Locator, value: String): Boolean = {
    val scope = fieldScope(field)
    val input = firstVisible(
      scope.locator("textarea, input:not([type='checkbox']):not([type='radio']):not([readonly])")
    ).orElse {
      firstVisible(field.locator("textarea, input:not([type='checkbox']):not([type='radio']):not([readonly])"))
    }.orNull
    if (input == null || input.count() == 0) return false

    val current = try input.inputValue() catch { case _: Exception => "" }
    if (current != null && current.nonEmpty) return true

    focusField(page, field)
    Utils.clickWithCursor(page, input)
    input.fill(value)
    true
  }

  // check box -> select
  def tryCheckCheckbox(page: Page, field: Locator): Boolean = {
    val scope = fieldScope(field)
    val checkbox = firstVisible(scope.locator("input[type='checkbox']")).orElse {
      firstVisible(field.locator("input[type='checkbox']"))
    }.orNull
    if (checkbox == null) return false
    if (checkbox.count() == 0) return false
    true
  }

  def trySetBoolean(page: Page, field: Locator, target: Boolean): Boolean = {
    val scope = fieldScope(field)

    val checkbox = firstVisible(scope.locator("input[type='checkbox']")).orElse {
      firstVisible(field.locator("input[type='checkbox']"))
    }.orNull
    if (checkbox != null && checkbox.count() > 0) {
      val current = try checkbox.isChecked() catch { case _: Exception => false }
      if (current != target) Utils.clickWithCursor(page, checkbox)
      return true
    }

    val switchControl = firstVisible(scope.locator(".ant-switch, button[role='switch']")).orElse {
      firstVisible(field.locator(".ant-switch, button[role='switch']"))
    }.orNull
    if (switchControl != null && switchControl.count() > 0) {
      val cls = try Option(switchControl.getAttribute("class")).getOrElse("") catch { case _: Exception => "" }
      val aria = try Option(switchControl.getAttribute("aria-checked")).getOrElse("") catch { case _: Exception => "" }
      val current = cls.contains("ant-switch-checked") || aria == "true"
      if (current != target) Utils.clickWithCursor(page, switchControl)
      return true
    }

    false
  }
}

// ═══════════════════════════════════════════════════════════════════
// 1. LoginControllerBuilder
//    new LoginControllerBuilder(ctx).login("user","pass").execute()
//    new LoginControllerBuilder(ctx).login("u","p").logout().execute()
// ═══════════════════════════════════════════════════════════════════

class LoginControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  def login(username: String, password: String): this.type = addStep(new ControllerStep {
    override def name = "Login"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      println(s"[Login] Navigating to ${TestDataConfig.baseUrl}")
      try {
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3000))
      } catch {
        case _: Exception =>
      }
      page.waitForTimeout(400)
      ctx.ensureFakeCursor()

      val loginSubmit = page.getByTestId("login-submit")
      println(s"[Login] login-submit count = ${loginSubmit.count()}, url = ${page.url()}")

      // login element is not seen, page is loading
      if (loginSubmit.count() == 0) {
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

      val usernameField = page.getByTestId("login-username")
        .or(page.getByPlaceholder("Username")).first()
      usernameField.waitFor(
        // Wait for text box visible
        new Locator.WaitForOptions()
          .setState(WaitForSelectorState.VISIBLE)
          .setTimeout(5000)
      )

      Utils.clickWithCursor(page, usernameField)
      usernameField.fill(username)

      // Verify input (username, password) is filled
      // if not, reenter
      val usernameVal = try usernameField.inputValue() catch { case _: Exception => "" }
      if (usernameVal != username) {
        usernameField.click(new Locator.ClickOptions().setForce(true))
        usernameField.fill("")
        usernameField.fill(username)
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
        passwordField.click()
        passwordField.fill(password)
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

  def logout(): this.type = addStep(new ControllerStep {
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
//    new NavigationControllerBuilder(ctx).createNewWorkflow().cleanWorkflow().execute()
// ═══════════════════════════════════════════════════════════════════

class NavigationControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  def openWorkflow(workflowId: String, workflowName: String = ""): this.type = addStep(new ControllerStep {
    override def name = s"Navigate to ${if (workflowName.nonEmpty) workflowName else workflowId}"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page

      // Redirect to given page
      page.navigate(
        s"${TestDataConfig.baseUrl}/dashboard/user/workflow/$workflowId",
        new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(20000)
      )
      try {
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3000))
      } catch {
        case _: Exception =>
      }
      ctx.ensureFakeCursor()
      Utils.waitVisible(page.getByTestId("navigation-workflow-canvas").first())
    }
  })

  def createNewWorkflow(): this.type = addStep(new ControllerStep {
    override def name = "Create New Workflow"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      if (page.url().contains("/workflow") && page.getByTestId("navigation-workflow-canvas").count() > 0) return

      page.navigate(
        s"${TestDataConfig.baseUrl}/dashboard",
        new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(20000)
      )

      val createBtn = page.getByTestId("navigation-create-workflow-button")
        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create Workflow")))
        .or(page.getByText("Create Workflow", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(createBtn)
      Utils.clickWithCursor(page, createBtn)

      try {
        page.getByTestId("navigation-workflow-canvas").first()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(10000))
      } catch {
        case _: Exception =>
          if (!page.url().contains("/workflow/")) {
            throw new RuntimeException("Create workflow did not open workflow editor.")
          }
      }
    }
  })

  def importWorkflow(jsonFilePath: String): this.type = addStep(new ControllerStep {
    override def name = s"Import Workflow from ${jsonFilePath.split("/").last}"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val filePath = java.nio.file.Paths.get(jsonFilePath)
      if (!java.nio.file.Files.exists(filePath)) {
        throw new RuntimeException(s"Workflow JSON not found: $jsonFilePath")
      }

      val beforeCount = page.locator("g.joint-cell.joint-element").count()

      val importBtn = page.getByTitle("import workflow")
        .or(page.getByTitle("import"))
        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("import")))
        .first()
      Utils.waitVisible(importBtn)

      val chooser = page.waitForFileChooser(new Page.WaitForFileChooserOptions().setTimeout(5000), () => {
        Utils.clickWithCursor(page, importBtn)
      })
      chooser.setFiles(filePath) // hide the file selection window

      var retries = 0
      while (page.locator("g.joint-cell.joint-element").count() <= beforeCount && retries < 24) {
        page.waitForTimeout(150)
        retries += 1
      } // Check import finish, # operator increase

      // Centralize all operator automatically
      val centerBtn = page.getByTitle("minimap-center-button")
      if (centerBtn.count() > 0) {
        Utils.clickWithCursor(page, centerBtn)
        page.waitForTimeout(120)
      }

      val afterCount = page.locator("g.joint-cell.joint-element").count()
      if (afterCount <= beforeCount) {
        println(s"[Import] No new operators after import (before=$beforeCount, after=$afterCount)")
      } else {
        println(s"[Import] Loaded ${afterCount - beforeCount} operators from ${jsonFilePath.split("/").last}")
      }
      page.waitForTimeout(100)
    }
  })

  def cleanWorkflow(): this.type = addStep(new ControllerStep {
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
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class OperatorControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  // load all operator metadata & category for later use
  private lazy val operatorMetadata = OperatorMetadataGenerator.allOperatorMetadata.operators
  private lazy val groupPathByName: Map[String, Seq[String]] = {
    buildGroupPathMap(OperatorGroupConstants.OperatorGroupOrderList)
  }

  // If present → return the draggable child element; if not → return the element itself.
  // For some UI components, the drag handle isn’t the outer container, but a specific element inside.
  private def dragHandle(item: Locator): Locator = {
    val draggable = FormHelpers.firstVisible(item.locator("[draggable='true']")).orNull
    if (draggable != null && draggable.count() > 0) draggable else item
  }

  def insertViaSearch(operatorName: String): this.type = addStep(new ControllerStep {
    override def name = s"Insert '$operatorName' via Search"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      // Find operator selection panel
      val operatorsMenu = page.getByTestId("operator-left-panel-operators-button")
        .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(operatorsMenu)
      Utils.clickWithCursor(page, operatorsMenu)

      val panelSearch = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      try {
        panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(1500))
      } catch {
        case _: Exception =>
          // Some states require one more click to switch to the Operators tab.
          Utils.clickWithCursor(page, operatorsMenu)
          panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(2500))
      }

      val searchInput = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      Utils.waitVisible(searchInput)
      Utils.clickWithCursor(page, searchInput)
      searchInput.fill(operatorName)

      val beforeCount = page.locator("g.joint-cell.joint-element").count()
      searchInput.press("Enter") // enter -> insert first match operator

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

      // Avoid overlapping operators
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

    // Check 2 node location, avoid overlapping
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
                     canvasPosition: (Double, Double) = (0.06, 0.2),
                     dragNextTo: Option[String] = None,
                     yOffset: Double = -40.0,
                     autoConnectToAnchor: Boolean = false,
                     fromPortIndex: Int = 0,
                     toPortIndex: Int = 0,
                     connectAdditionalFrom: Option[String] = None,
                     connectAdditionalFromPortIndex: Int = 0,
                     connectAdditionalToInputIndex: Option[Int] = None
                   ): this.type = addStep(new ControllerStep {
    override def name = s"Insert '$operatorName' via Drag${dragNextTo.map(n => s" (next to $n)").getOrElse("")}"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      // ── Open operator panel & resolve source ──
      val operatorsMenu = page.getByTestId("operator-left-panel-operators-button")
        .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(operatorsMenu)
      Utils.clickWithCursor(page, operatorsMenu)

      val panelSearch = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      try {
        panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(1500))
      } catch {
        case _: Exception =>
          // Some states require one more click to switch to the Operators tab.
          Utils.clickWithCursor(page, operatorsMenu)
          panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(2500))
      }

      // Locate operator from group
      val metadata = metadataFor(operatorName, operatorType)
      val groupPath = metadata.flatMap(m => groupPathByName.get(m.additionalMetadata.operatorGroupName)).getOrElse(Seq.empty)
      val hierarchyOperator = resolveByGroupPath(page, operatorName, operatorType)
      val enforceHierarchy = groupPath.contains(OperatorGroupConstants.VISUALIZATION_GROUP)

      val operator = hierarchyOperator.map { op =>
        println(s"[Operator] Resolved via hierarchy: ${groupPath.mkString(" -> ")}")
        dragHandle(op)
      }.orElse {
        if (enforceHierarchy) {
          println(
            s"[Operator] Hierarchy resolve failed for '$operatorName' at path '${groupPath.mkString(" -> ")}', fallback to search"
          )
        }
        None
      }.getOrElse {
        println(s"[Operator] Hierarchy fallback to search for '$operatorName'")
        val searchInput = page.getByTestId("operator-search-input")
          .or(page.getByPlaceholder("search operator")).first()
        Utils.waitVisible(searchInput)
        Utils.clickWithCursor(page, searchInput)
        searchInput.fill("")
        page.waitForTimeout(150)
        dragHandle(resolveOperatorSource(page, operatorName, operatorType))
      }
      operator.scrollIntoViewIfNeeded()
      page.waitForTimeout(80)

      // ── Prepare canvas ──
      val canvas = page.getByTestId("navigation-workflow-canvas")
        .or(page.locator("svg[joint-selector='svg'], svg#v-2")).first()
      Utils.waitVisible(canvas)
      canvas.scrollIntoViewIfNeeded()
      page.waitForTimeout(60)

      val beforeCount = page.locator("g.joint-cell.joint-element").count()
      val beforeLinkCount = page.locator("g.joint-cell.joint-link").count()
      val canvasBox = canvas.boundingBox()
      if (canvasBox == null) throw new RuntimeException("Drag failed: missing canvas bounding box")

      // ── Calculate drop position ──
      val anchorNode: Option[Locator] = dragNextTo.flatMap(findNodeByType(page, _))

      if (dragNextTo.isDefined && anchorNode.isEmpty) {
        println(s"[Operator] Warning: dragNextTo='${dragNextTo.get}' not found on canvas, using default position")
      }

      val (tgtX, tgtY) = anchorNode.flatMap { anchor =>
        val box = Utils.cellBox(anchor)
        if (box != null) {
          val spacing = 110.0
          Some((
            math.min(canvasBox.x + canvasBox.width - 30, box.x + box.width + spacing),
            box.y + box.height / 2.0 + yOffset
          ))
        } else None
      }.getOrElse {
        val index = Math.max(0, beforeCount)
        val baseX = canvasBox.x + canvasBox.width * canvasPosition._1
        val baseY = canvasBox.y + canvasBox.height * canvasPosition._2
        (
          math.min(canvasBox.x + canvasBox.width - 40, baseX + (index % 4) * 180),
          math.min(canvasBox.y + canvasBox.height - 40, baseY + (index / 4) * 120)
        )
      }

      // ── Perform drag with fallbacks ──
      performDrag(page, operator, tgtX, tgtY)

      val targetCount = beforeCount + 1
      if (!waitForNodeCountAtLeast(page, targetCount, maxRetries = 20)) {
        val searchInput = page.getByTestId("operator-search-input")
          .or(page.getByPlaceholder("search operator")).first()
        Utils.waitVisible(searchInput)
        Utils.clickWithCursor(page, searchInput)
        searchInput.fill("")
        page.waitForTimeout(80)
        val retryOperator = dragHandle(resolveOperatorSource(page, operatorName, operatorType))
        performDrag(page, retryOperator, tgtX, tgtY)
      }
      if (!waitForNodeCountAtLeast(page, targetCount, maxRetries = 20)) {
        // Last fallback: insert through search + Enter when drag source is flaky.
        val searchInput = page.getByTestId("operator-search-input")
          .or(page.getByPlaceholder("search operator")).first()
        Utils.waitVisible(searchInput)
        Utils.clickWithCursor(page, searchInput)
        searchInput.fill("")
        page.waitForTimeout(60)
        searchInput.fill(operatorName)
        page.waitForTimeout(100)
        searchInput.press("Enter")
      }
      if (!waitForNodeCountAtLeast(page, targetCount, maxRetries = 20)) {
        throw new RuntimeException(
          s"Insert failed for '$operatorName' (${operatorType.getOrElse("unknown")}). " +
            s"Canvas count did not increase from $beforeCount."
        )
      }

      val centerBtn = page.getByTitle("minimap-center-button")
      if (centerBtn.count() > 0) {
        Utils.clickWithCursor(page, centerBtn)
        page.waitForTimeout(120)
      }

      // ── Click the new node to select it ──
      val newNode = Utils.waitVisible(page.locator("g.joint-cell.joint-element").nth(beforeCount))
      if (newNode.count() > 0) {
        val body = newNode.locator("rect.body").first()
        if (body.count() > 0) Utils.clickWithCursor(page, body) else Utils.clickWithCursor(page, newNode)
      }

      if (autoConnectToAnchor && dragNextTo.isDefined && anchorNode.exists(_.count() > 0) && newNode.count() > 0) {
        val connected = tryAutoConnect(
          page,
          anchorNode.get,
          newNode,
          fromPortIndex = fromPortIndex,
          toPortIndex = toPortIndex
        )
        if (connected) {
          var retries = 0
            while (page.locator("g.joint-cell.joint-link").count() <= beforeLinkCount && retries < 10) {
              page.waitForTimeout(80)
              retries += 1
            }
          if (page.locator("g.joint-cell.joint-link").count() <= beforeLinkCount) {
            println(s"[Operator] Warning: explicit connect attempted but no new link was detected for '$operatorName'")
          }
        } else {
          println(s"[Operator] Warning: could not locate connectable ports for '$operatorName'")
        }
      }

      if (connectAdditionalFrom.isDefined && newNode.count() > 0) {
        val additionalFromNode = findNodeByType(page, connectAdditionalFrom.get)
        if (additionalFromNode.isEmpty) {
          println(s"[Operator] Warning: connectAdditionalFrom='${connectAdditionalFrom.get}' not found on canvas")
        } else {
          val beforeExtraLinkCount = page.locator("g.joint-cell.joint-link").count()
          val connected = tryAutoConnect(
            page,
            additionalFromNode.get,
            newNode,
            fromPortIndex = connectAdditionalFromPortIndex,
            toPortIndex = connectAdditionalToInputIndex.getOrElse(0)
          )
          if (connected) {
            var retries = 0
            while (page.locator("g.joint-cell.joint-link").count() <= beforeExtraLinkCount && retries < 10) {
              page.waitForTimeout(80)
              retries += 1
            }
            if (page.locator("g.joint-cell.joint-link").count() <= beforeExtraLinkCount) {
              println(s"[Operator] Warning: additional connect attempted but no new link was detected for '$operatorName'")
            }
          } else {
            println(s"[Operator] Warning: could not connect additional input for '$operatorName'")
          }
        }
      }

      // ── Reposition only for default placement mode.
      if (dragNextTo.isEmpty) {
        val referenceNode = anchorNode
          .filter(_.count() > 0)
          .orElse(if (beforeCount > 0) Some(page.locator("g.joint-cell.joint-element").nth(beforeCount - 1)) else None)
          .orNull

        if (referenceNode != null && referenceNode.count() > 0) {
          val refBox = Utils.cellBox(referenceNode)
          val newBox = Utils.cellBox(newNode)
          if (refBox != null && newBox != null) {
            val spacing = 220.0
            val targetCenterX = refBox.x + refBox.width + spacing + newBox.width / 2.0
            val targetCenterY = refBox.y + refBox.height / 2.0
            val currentCenterX = newBox.x + newBox.width / 2.0
            val currentCenterY = newBox.y + newBox.height / 2.0
            Utils.nudgeCell(page, newNode, targetCenterX - currentCenterX, targetCenterY - currentCenterY)
            page.waitForTimeout(120)
          }
          Utils.ensureSeparated(page, referenceNode, newNode)
        }
      }
    }
  })

  private def findNodeByType(page: Page, operatorTypeOrName: String): Option[Locator] = {
    val normalized = normalize(operatorTypeOrName)
    val relaxed = normalized.replace("operator", "")

    def matches(candidate: String): Boolean = {
      val c = normalize(candidate)
      c.nonEmpty && (
        c.contains(normalized) ||
          normalized.contains(c) ||
          (relaxed.nonEmpty && (c.contains(relaxed) || relaxed.contains(c)))
        )
    }

    val cells = page.locator("g.joint-cell.joint-element")
    val count = cells.count()
    var i = 0
    while (i < count) {
      val cell = cells.nth(i)
      val testId = try Option(cell.getAttribute("data-testid")).getOrElse("")
      catch { case _: Exception => "" }
      if (matches(testId)) return Some(cell)

      val modelId = try Option(cell.getAttribute("model-id")).getOrElse("")
      catch { case _: Exception => "" }
      if (matches(modelId)) return Some(cell)

      val label = cell.locator("text.operator-name, .texera-operator-label, text").first()
      val labelText = try {
        if (label.count() > 0) Option(label.innerText()).getOrElse("") else ""
      } catch { case _: Exception => "" }
      if (matches(labelText)) {
        return Some(cell)
      }
      i += 1
    }
    None
  }

  private def waitForNodeCountAtLeast(page: Page, targetCount: Int, maxRetries: Int): Boolean = {
    var retries = 0
    while (page.locator("g.joint-cell.joint-element").count() < targetCount && retries < maxRetries) {
      page.waitForTimeout(100)
      retries += 1
    }
    page.locator("g.joint-cell.joint-element").count() >= targetCount
  }

  private def performDrag(page: Page, source: Locator, targetX: Double, targetY: Double): Unit = {
    val src = dragHandle(source)
    val srcBox = src.boundingBox()
    if (srcBox == null) throw new RuntimeException("Drag failed: missing source bounding box")
    val srcX = srcBox.x + srcBox.width / 2.0
    val srcY = srcBox.y + srcBox.height / 2.0
    page.mouse().move(srcX, srcY, new Mouse.MoveOptions().setSteps(25))
    page.mouse().down()
    page.mouse().move(targetX, targetY, new Mouse.MoveOptions().setSteps(35))
    page.mouse().up()
  }

  private def tryAutoConnect(
                              page: Page,
                              fromNode: Locator,
                              toNode: Locator,
                              fromPortIndex: Int = 0,
                              toPortIndex: Int = 0
                            ): Boolean = {
    def collectPortCenters(node: Locator, io: String): Seq[(Option[Int], Double, Double)] = {
      val selector =
        if (io == "output")
          "circle[port-group='output'], circle[port-group='out'], circle[port*='output'], .outPorts circle, circle[magnet='true'][port*='output']"
        else
          "circle[port-group='input'], circle[port-group='in'], circle[port*='input'], .inPorts circle, circle[magnet='true'][port*='input']"

      val ports = node.locator(selector)
      val total = ports.count()
      var i = 0
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Option[Int], Double, Double)]
      while (i < total) {
        val p = ports.nth(i)
        try {
          if (p.isVisible()) {
            val b = p.boundingBox()
            if (b != null) {
              val attr = Option(p.getAttribute("port"))
                .orElse(Option(p.getAttribute("data-port")))
                .getOrElse("")
              val idx = (".*?(?:input|output)-([0-9]+).*").r
                .findFirstMatchIn(attr)
                .map(_.group(1).toInt)
              buf += ((idx, b.x + b.width / 2.0, b.y + b.height / 2.0))
            }
          }
        } catch {
          case _: Exception =>
        }
        i += 1
      }
      buf.sortBy { case (idx, _, y) => (idx.getOrElse(Int.MaxValue), y) }.toSeq
    }

    def pickCenter(ports: Seq[(Option[Int], Double, Double)], index: Int): Option[(Double, Double)] = {
      ports.find(_._1.contains(index))
        .orElse(ports.lift(index))
        .map { case (_, x, y) => (x, y) }
    }

    val fromPort = pickCenter(collectPortCenters(fromNode, "output"), fromPortIndex)
    val toPort = pickCenter(collectPortCenters(toNode, "input"), toPortIndex)
    if (fromPort.isEmpty || toPort.isEmpty) return false

    page.mouse().move(fromPort.get._1, fromPort.get._2, new Mouse.MoveOptions().setSteps(12))
    page.mouse().down()
    page.mouse().move(toPort.get._1, toPort.get._2, new Mouse.MoveOptions().setSteps(20))
    page.mouse().up()
    page.waitForTimeout(120)
    true
  }

  def connectOperators(
                        fromOperator: String,
                        toOperator: String,
                        fromPortIndex: Int = 0,
                        toPortIndex: Int = 0
                      ): this.type = addStep(new ControllerStep {
    override def name = s"Connect $fromOperator:$fromPortIndex -> $toOperator:$toPortIndex"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val fromNode = findNodeByType(page, fromOperator)
        .getOrElse(throw new RuntimeException(s"Node not found: $fromOperator"))
      val toNode = findNodeByType(page, toOperator)
        .getOrElse(throw new RuntimeException(s"Node not found: $toOperator"))

      if (!tryAutoConnect(page, fromNode, toNode, fromPortIndex = fromPortIndex, toPortIndex = toPortIndex)) {
        throw new RuntimeException(
          s"Failed to connect $fromOperator:$fromPortIndex -> $toOperator:$toPortIndex"
        )
      }
    }
  })

  private def metadataFor(operatorName: String, operatorType: Option[String]) = {
    val normalizedName = normalize(operatorName)
    operatorType.filter(_.nonEmpty)
      .flatMap(t => operatorMetadata.find(_.operatorType == t))
      .orElse(operatorMetadata.find(m => normalize(m.additionalMetadata.userFriendlyName) == normalizedName))
  }

  private def resolveOperatorSource(
                                     page: Page,
                                     operatorName: String,
                                     operatorType: Option[String]
                                   ): Locator = {
    val byType = operatorType.filter(_.nonEmpty).map(t => page.getByTestId(s"operator-item-$t").first())
    byType.foreach { loc =>
      if (loc.count() > 0) return dragHandle(loc)
    }

    val leftPanel = page.locator("#left-container")
    val exactLabel = FormHelpers.firstVisible(leftPanel.getByText(operatorName, new Locator.GetByTextOptions().setExact(true)))
    exactLabel.foreach { label =>
      val row = label.locator("xpath=ancestor-or-self::*[@data-testid and starts-with(@data-testid,'operator-item-')][1]").first()
      if (row.count() > 0) return dragHandle(row)
      return dragHandle(label)
    }

    resolveByGroupPath(page, operatorName, operatorType).foreach(item => return dragHandle(item))

    val resultItems = page.locator("#left-container [data-testid^='operator-item-']")
    FormHelpers.firstVisible(resultItems).foreach(item => return dragHandle(item))

    val fuzzy = FormHelpers.firstVisible(leftPanel.locator(s".operator-label:has-text('$operatorName')"))
    fuzzy.map(dragHandle).getOrElse {
      throw new RuntimeException(s"Cannot find operator source for '$operatorName' (${operatorType.getOrElse("unknown")})")
    }
  }

  private def resolveByGroupPath(
                                  page: Page,
                                  operatorName: String,
                                  operatorType: Option[String]
                                ): Option[Locator] = {
    val metadata = metadataFor(operatorName, operatorType)
    val path = metadata.flatMap(m => groupPathByName.get(m.additionalMetadata.operatorGroupName)).getOrElse(Seq.empty)
    if (path.isEmpty) return None

    val leftPanel = page.locator("#left-container")
    var scope: Locator = leftPanel

    path.zipWithIndex.foreach { case (group, depth) =>
      val header = findHeaderInScope(scope, group).orElse(findHeaderByDepth(leftPanel, group, depth)).orNull
      if (header == null || header.count() == 0) return None

      val panel = header.locator("xpath=ancestor::*[contains(@class,'ant-collapse-item')][1]").first()
      if (panel.count() > 0) {
        val panelClass = Option(panel.getAttribute("class")).getOrElse("")
        if (!panelClass.contains("ant-collapse-item-active")) {
          clickGroupHeader(page, header)
          page.waitForTimeout(220)
          val afterClass = Option(panel.getAttribute("class")).getOrElse("")
          if (!afterClass.contains("ant-collapse-item-active")) {
            clickGroupHeader(page, header)
            page.waitForTimeout(220)
          }
        }
        scope = panel
      } else {
        // Fallback for non-collapse style groups.
        clickGroupHeader(page, header)
        page.waitForTimeout(220)
        scope = leftPanel
      }
    }

    operatorType.filter(_.nonEmpty).flatMap { t =>
      FormHelpers.firstVisible(scope.getByTestId(s"operator-item-$t"))
    }.foreach(item => return Some(item))

    val exact = scope.getByText(operatorName, new Locator.GetByTextOptions().setExact(true)).first()
    if (exact.count() == 0) return None
    val row = exact.locator("xpath=ancestor-or-self::*[@data-testid and starts-with(@data-testid,'operator-item-')][1]").first()
    if (row.count() > 0) Some(row) else Some(exact)
  }

  private def findHeaderInScope(scope: Locator, groupName: String): Option[Locator] = {
    val headers = scope.locator(".ant-collapse-header")
    val count = headers.count()
    val target = normalize(groupName)
    var i = 0
    while (i < count) {
      val header = headers.nth(i)
      val visible = try header.isVisible() catch { case _: Exception => false }
      val label = try Option(header.innerText()).getOrElse("").replaceAll("\\s+", " ").trim
      catch { case _: Exception => "" }
      val norm = normalize(label)
      if (visible && (norm == target || norm.contains(target) || target.contains(norm))) return Some(header)
      i += 1
    }
    None
  }

  private def findHeaderByDepth(root: Locator, groupName: String, depth: Int): Option[Locator] = {
    val headers = root.locator(s".operator-group[data-depth='$depth'] .ant-collapse-header")
    val count = headers.count()
    val target = normalize(groupName)
    var i = 0
    while (i < count) {
      val header = headers.nth(i)
      val visible = try header.isVisible() catch { case _: Exception => false }
      val label = try Option(header.innerText()).getOrElse("").replaceAll("\\s+", " ").trim
      catch { case _: Exception => "" }
      val norm = normalize(label)
      if (visible && (norm == target || norm.contains(target) || target.contains(norm))) return Some(header)
      i += 1
    }
    None
  }

  private def clickGroupHeader(page: Page, header: Locator): Unit = {
    try header.scrollIntoViewIfNeeded() catch { case _: Exception => }
    val arrow = header.locator(".ant-collapse-arrow, i.anticon-right, i.anticon-down").first()
    if (arrow.count() > 0) {
      try Utils.clickWithCursor(page, arrow, steps = 10)
      catch { case _: Exception => Utils.clickWithCursor(page, header, steps = 10) }
    } else {
      Utils.clickWithCursor(page, header, steps = 10)
    }
  }

  private def normalize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]", "")

  private def buildGroupPathMap(groups: List[GroupInfo]): Map[String, Seq[String]] = {
    def walk(items: List[GroupInfo], prefix: Seq[String]): Map[String, Seq[String]] = {
      items.flatMap { g =>
        val path = prefix :+ g.groupName
        val current = Map(g.groupName -> path)
        val children = Option(g.children).getOrElse(List.empty)
        current ++ walk(children, path)
      }.toMap
    }
    walk(groups, Seq.empty)
  }
}

// ═══════════════════════════════════════════════════════════════════
// 5. DatasetControllerBuilder
//    new DatasetControllerBuilder(ctx)
//      .datasetName("my-dataset")
//      .datasetVersion("v1")
//      .file("data.csv")
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class DatasetControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  private var _datasetName: String = _
  private var _versionName: String = _
  private var _fileName: Option[String] = None

  def datasetName(name: String): this.type = { _datasetName = name; this }
  def datasetVersion(version: String): this.type = { _versionName = version; this }
  def file(name: String): this.type = { _fileName = Some(name); this }

  def fromConfig(datasetKey: String): this.type = {
    val ds = TestDataConfig.datasets(datasetKey)
    _datasetName = ds.name
    _versionName = ds.version
    this
  }

  override def execute(): Unit = {
    require(_datasetName != null && _datasetName.nonEmpty, "datasetName is required")
    require(_versionName != null && _versionName.nonEmpty, "datasetVersion is required")
    addStep(new FileSelectionStep(_datasetName, _versionName, _fileName))
    super.execute()
  }

  private class FileSelectionStep(datasetName: String, versionName: String, fileName: Option[String]) extends ControllerStep {
    override def name = s"Select File from $datasetName/$versionName"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page

      // Local helper: returns Locator (not Option) with .first() fallback.
      // Different contract from FormHelpers.firstVisible which returns Option[Locator].
      def firstVisibleOrFirst(locator: Locator): Locator = {
        FormHelpers.firstVisible(locator).getOrElse(locator.first())
      }

      val selectBtn = firstVisibleOrFirst(
        page.locator(
          "[data-testid='dataset-file-selection-open'], [data-testid='file-selection-open']"
        )
      )
      Utils.waitVisible(selectBtn)
      Utils.clickWithCursor(page, selectBtn)

      val modal = firstVisibleOrFirst(
        page.locator(
          "[data-testid='dataset-file-selection-modal'], [data-testid='file-selection-modal'], .ant-modal-wrap .ant-modal-content"
        )
      )
      Utils.waitVisible(modal)

      val datasetBox = firstVisibleOrFirst(
        modal.locator(
          "[data-testid='dataset-file-selection-dataset'] .ant-select-selector, [data-testid='file-selection-dataset'] .ant-select-selector, .select-dataset .ant-select-selector"
        )
      )
      Utils.waitVisible(datasetBox)
      Utils.clickWithCursor(page, datasetBox)
      page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      Utils.chooseDropdownOptionByText(page, datasetName)

      val versionBoxCandidates = modal.locator(
        "[data-testid='dataset-file-selection-version'] .ant-select-selector, [data-testid='file-selection-version'] .ant-select-selector, .select-version .ant-select-selector"
      )
      if (versionBoxCandidates.count() > 0) {
        val versionBox = firstVisibleOrFirst(versionBoxCandidates)
        Utils.waitVisible(versionBox)
        Utils.clickWithCursor(page, versionBox)
        page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        Utils.chooseDropdownOptionByText(page, versionName)
      }

      val fileTree = firstVisibleOrFirst(
        modal.locator(
          "[data-testid='dataset-file-selection-filetree'], [data-testid='file-selection-filetree'], .ant-tree"
        )
      )
      Utils.waitVisible(fileTree)
      val fileNode = fileName match {
        case Some(fn) =>
          val exact = fileTree.getByText(fn, new Locator.GetByTextOptions().setExact(true)).first()
          if (exact.count() > 0) exact else fileTree.getByText(fn).first()
        case None =>
          firstVisibleOrFirst(fileTree.locator("span[title*='.csv'], .ant-tree-node-content-wrapper"))
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

class FormControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  // Collect all operator schema titles keyed by normalized field key.
  // This replaces the need for hardcoded aliases in most cases.
  private lazy val metadataTitlesByKey: Map[String, Seq[String]] = {
    val grouped = scala.collection.mutable.Map.empty[String, scala.collection.mutable.LinkedHashSet[String]]
    OperatorMetadataGenerator.allOperatorMetadata.operators.foreach { metadata =>
      val properties = metadata.jsonSchema.path("properties")
      if (properties.isObject) {
        properties.fields().asScala.foreach { entry =>
          val key = normalize(entry.getKey)
          val titleNode = entry.getValue.path("title")
          if (!titleNode.isMissingNode && !titleNode.isNull) {
            val title = titleNode.asText("").trim
            if (title.nonEmpty) {
              val acc = grouped.getOrElseUpdate(key, scala.collection.mutable.LinkedHashSet.empty[String])
              acc += title
            }
          }
        }
      }
    }
    grouped.view.mapValues(_.toSeq).toMap
  }

  // Single normalize function used for both keys and labels.
  private def normalize(s: String): String =
    s.replaceAll("[^A-Za-z0-9]", "").toLowerCase

  private def labelCandidates(fieldKey: String): Seq[String] = {
    // Derive a human-readable label from camelCase/snake_case key
    val pretty = fieldKey
      .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
      .replaceAll("_", " ")
      .trim
      .split("\\s+")
      .map(_.toLowerCase.capitalize)
      .mkString(" ")

    val normalized = normalize(fieldKey)
    val metadataTitles = metadataTitlesByKey.getOrElse(normalized, Seq.empty)

    // Metadata titles first (most authoritative), then pretty-printed fallback
    (metadataTitles :+ pretty).map(_.trim).filter(_.nonEmpty).distinct
  }

  private def propertyEditorRoot(page: Page): Locator = {
    val root = page.locator("#property-editor").first()
    if (root.count() > 0) root else page.locator("body").first()
  }

  // Normalized comparison handles "X-Column" vs "X Column" vs "X_Column"
  private def hasExactLabel(field: Locator, expected: String): Boolean = {
    val labels = field.locator("label")
    val count = labels.count()
    val expectedNorm = normalize(expected)
    var i = 0
    while (i < count) {
      val l = labels.nth(i)
      val text = try l.innerText().trim catch { case _: Exception => "" }
      if (normalize(text) == expectedNorm) return true
      i += 1
    }
    false
  }

  private def hasEditableControl(field: Locator): Boolean =
    field.locator("nz-select, .ant-select, input, textarea, .ant-switch, button[role='switch']").count() > 0

  private def formlyDepth(field: Locator): Int =
    field.locator("xpath=ancestor::formly-field").count()

  private def resolveByLabel(root: Locator, labels: Seq[String], leafOnly: Boolean): Locator = {
    val fields = root.locator("formly-field")
    val total = fields.count()
    var best: Locator = root.locator("__not_found__").first()
    var bestDepth = -1
    var i = 0
    while (i < total) {
      val candidate = fields.nth(i)
      val visible = try candidate.isVisible() catch { case _: Exception => false }
      val hasNested = candidate.locator("formly-field").count() > 0
      if (visible && (!leafOnly || !hasNested) && hasEditableControl(candidate)) {
        var matched = false
        var j = 0
        while (j < labels.size && !matched) {
          if (hasExactLabel(candidate, labels(j))) matched = true
          j += 1
        }
        if (matched) {
          val d = formlyDepth(candidate)
          if (d >= bestDepth) {
            best = candidate
            bestDepth = d
          }
        }
      }
      i += 1
    }
    best
  }

  private def resolveField(page: Page, fieldKey: String): Locator = {
    val container = page.locator("#property-editor")
    val root = propertyEditorRoot(page)
    val labels = labelCandidates(fieldKey)

    val byLeaf = resolveByLabel(root, labels, leafOnly = true)
    if (byLeaf.count() > 0) return byLeaf

    val byAny = resolveByLabel(root, labels, leafOnly = false)
    if (byAny.count() > 0) return byAny

    for (label <- labels) {
      val field = container.locator(
        s"formly-field:has(> formly-wrapper-nz-form-field):has(label:text-is('$label'))"
      )
      if (field.count() > 0) return field.first()
    }

    // fallback
    page.locator(s"[data-testid^='form-field-$fieldKey']").first()
  }

  private def resolveFieldInScope(scope: Locator, fieldKey: String): Locator = {
    val labels = labelCandidates(fieldKey)
    val byLeaf = resolveByLabel(scope, labels, leafOnly = true)
    if (byLeaf.count() > 0) return byLeaf

    val byAny = resolveByLabel(scope, labels, leafOnly = false)
    if (byAny.count() > 0) return byAny

    scope.locator(s"[data-testid^='form-field-$fieldKey']").last()
  }

  private def resolveArraySection(root: Locator, fieldKey: String): Locator = {
    val labels = labelCandidates(fieldKey)
    var i = 0
    while (i < labels.size) {
      val label = labels(i)
      val byFormly = root.locator(s"formly-field:has(label:text-is('$label'))").last()
      if (byFormly.count() > 0) return byFormly

      val byText = root.getByText(label, new Locator.GetByTextOptions().setExact(false)).first()
      if (byText.count() > 0) {
        val section = byText.locator("xpath=ancestor::*[self::formly-field or self::div][1]").first()
        if (section.count() > 0) return section
      }
      i += 1
    }
    root
  }

  private def resolveArrayAddButton(section: Locator, root: Locator): Locator = {
    val inSection = section.locator(
      "button:has(i.anticon-plus), button.ant-btn-circle, .ant-btn:has-text('Add')"
    ).last()
    if (inSection.count() > 0) return inSection
    root.locator(
      "button:has(i.anticon-plus), button.ant-btn-circle, .ant-btn:has-text('Add')"
    ).last()
  }

  private def fillArrayItemsNow(
    page: Page,
    fieldKey: String,
    items: Seq[Map[String, String]]
  ): Unit = {
    val root = propertyEditorRoot(page)
    val section = resolveArraySection(root, fieldKey)

    items.foreach { itemValues =>
      val addBtn = resolveArrayAddButton(section, root)
      if (addBtn.count() == 0) {
        throw new RuntimeException(s"Add button not found for array field: $fieldKey")
      }
      Utils.clickWithCursor(page, addBtn)
      page.waitForTimeout(220)

      itemValues.foreach { case (subKey, value) =>
        val field = resolveFieldInScope(section, subKey)
        if (field.count() == 0) {
          println(s"  Sub-field not found: $subKey")
        } else if (
          !FormHelpers.tryFillSelect(page, field, Some(value)) &&
          !FormHelpers.tryFillText(page, field, value)
        ) {
          println(s"  No supported input for sub-field: $subKey")
        }
        page.waitForTimeout(120)
      }
    }
  }

  def fillField(fieldName: String, value: String): this.type = addStep(new ControllerStep {
    override def name = s"Fill Field '$fieldName'"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val fieldByKey = resolveField(page, fieldName)
      if (fieldByKey.count() > 0 && FormHelpers.tryFillFieldContainer(page, fieldByKey, value)) return

      val label = page.getByText(fieldName, new Page.GetByTextOptions().setExact(true)).first()
      Utils.waitVisible(label)
      val fieldContainer = label.locator("xpath=ancestor::formly-field[1]")
      if (FormHelpers.tryFillFieldContainer(page, fieldContainer, value)) return

      throw new RuntimeException(s"No supported input found for field: $fieldName")
    }
  })

  def fillFieldValues(values: Map[String, String]): this.type = addStep(new ControllerStep {
    override def name = s"Fill ${values.size} Field Values"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      values.foreach { case (fieldKey, value) =>
        val field = resolveField(page, fieldKey)
        if (field.count() == 0) {
          println(s"  Field not found: $fieldKey")
        } else {
          if (!FormHelpers.tryFillSelect(page, field, Some(value)) &&
            !FormHelpers.tryFillText(page, field, value)) {
            println(s"  No supported input for field: $fieldKey")
          }
        }
      }
    }
  })

  def fillFieldJsonValues(values: Map[String, JsonNode]): this.type = addStep(new ControllerStep {
    override def name = s"Fill ${values.size} Field Values (JSON)"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      values.foreach { case (fieldKey, node) =>
        val field = resolveField(page, fieldKey)

        val actualLabel = field.locator("label").first()
        val labelText = if (actualLabel.count() > 0) actualLabel.innerText().trim() else "NO LABEL"
        println(s"  [DEBUG] key='$fieldKey' → resolved label='$labelText' found=${field.count() > 0}")

        if (field.count() == 0) {
          println(s"  Field not found: $fieldKey")
        } else if (node == null || node.isNull) {
          // skip null
        } else if (node.isBoolean) {
          val target = node.asBoolean()
          if (target) {
            if (!FormHelpers.trySetBoolean(page, field, target = true)) {
              println(s"  No supported boolean input for field: $fieldKey")
            }
          } else {
            // keep default false to avoid unnecessary toggle for demos
          }
        } else if (node.isTextual || node.isNumber) {
          val value = node.asText()
          if (!FormHelpers.tryFillSelect(page, field, Some(value)) &&
            !FormHelpers.tryFillText(page, field, value)) {
            println(s"  No supported input for field: $fieldKey")
          }
        } else if (node.isArray) {
          val elements = node.elements().asScala.toSeq.filter(n => n != null && !n.isNull)
          val allObjects = elements.nonEmpty && elements.forall(_.isObject)
          if (allObjects) {
            val rows = elements.map { row =>
              row.fields().asScala
                .filter(e => e.getValue != null && !e.getValue.isNull)
                .map(e => e.getKey -> e.getValue.asText())
                .toMap
            }
            fillArrayItemsNow(page, fieldKey, rows)
          } else {
            val values = elements
              .filter(n => n.isTextual || n.isNumber)
              .map(_.asText())
            if (!FormHelpers.tryFillSelectMany(page, field, values)) {
              println(s"  No supported array-select input for field: $fieldKey")
            }
          }
        } else {
          println(s"  Skip unsupported JSON type for field: $fieldKey")
        }
      }
    }
  })

  def fillArrayItems(
    fieldKey: String,
    items: Seq[Map[String, String]]
  ): this.type = addStep(new ControllerStep {
    override def name = s"Fill ${items.size} items in '$fieldKey'"
    override def run(ctx: ControllerContext): Unit = {
      fillArrayItemsNow(ctx.page, fieldKey, items)
    }
  })

  def autoFillRequired(maxFields: Int = 12, defaultText: String = "test"): this.type = addStep(new ControllerStep {
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

  def autoFillFields(fieldKeys: Seq[String], defaultText: String = "test"): this.type = addStep(new ControllerStep {
    override def name = s"Auto Fill ${fieldKeys.size} Fields"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      fieldKeys.foreach { key =>
        val field = resolveField(page, key)
        if (field.count() == 0) {
          println(s"  Field not found: $key")
        } else if (!FormHelpers.isFieldRequired(field) && FormHelpers.isBooleanLikeField(field)) {
          println(s"  Skip optional boolean field: $key")
        } else {
          if (!FormHelpers.tryFillSelect(page, field) &&
            !FormHelpers.tryFillText(page, field, defaultText) &&
            !FormHelpers.tryCheckCheckbox(page, field)) {
            println(s"  No supported input for field: $key")
          }
        }
      }
    }
  })
}

// ═══════════════════════════════════════════════════════════════════
// 7. ExecutionControllerBuilder
//    new ExecutionControllerBuilder(ctx)
//      .runWorkflowAndWait()
//      .openResultPanel()
//      .execute()
// ═══════════════════════════════════════════════════════════════════

class ExecutionControllerBuilder(ctx: ControllerContext)
  extends ControllerBuilder(ctx) {

  private def runButton(page: Page): Locator =
    page.locator("#run-button").first()

  private def buttonText(button: Locator): String = {
    try Option(button.innerText()).getOrElse("").replaceAll("\\s+", " ").trim
    catch { case _: Exception => "" }
  }

  private def isDisabled(button: Locator): Boolean = {
    val disabledAttr = try Option(button.getAttribute("disabled")).getOrElse("") catch { case _: Exception => "" }
    val cls = try Option(button.getAttribute("class")).getOrElse("") catch { case _: Exception => "" }
    disabledAttr.nonEmpty || cls.contains("ant-btn-disabled")
  }

  private def createOrSelectComputingUnit(page: Page, timeoutMs: Int): Boolean = {
    val dropdownBtn = page.locator(".computing-units-dropdown-button").first()
    if (dropdownBtn.count() == 0) return false
    Utils.waitVisible(dropdownBtn)
    Utils.clickWithCursor(page, dropdownBtn)

    val dropdown = page.locator(".computing-units-dropdown").first()
    dropdown.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000))

    val existing = dropdown.locator("#computing-unit-option:not(.ant-dropdown-menu-item-disabled)").first()
    if (existing.count() > 0) {
      Utils.clickWithCursor(page, existing)
      page.waitForTimeout(500)
      return true
    }

    val createEntry = dropdown.locator(".create-computing-unit").first()
    if (createEntry.count() == 0) return false
    Utils.clickWithCursor(page, createEntry)

    val modal = page.locator(".ant-modal-wrap .ant-modal-content").last()
    modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(8000))

    val typeSelector = modal.locator(".type-selection .ant-select-selector").first()
    if (typeSelector.count() > 0) {
      Utils.clickWithCursor(page, typeSelector)
      Utils.chooseDropdownOptionByText(page, "Local")
      page.waitForTimeout(150)
    }

    val nameInput = modal.locator(".unit-name-input").first()
    if (nameInput.count() > 0) {
      val current = try nameInput.inputValue() catch { case _: Exception => "" }
      if (current.trim.isEmpty) nameInput.fill("My Computing Unit")
    }

    val uriInput = modal.locator(".unit-uri-input").first()
    if (uriInput.count() > 0) {
      val current = try uriInput.inputValue() catch { case _: Exception => "" }
      if (current.trim.isEmpty) uriInput.fill(s"${TestDataConfig.baseUrl}/wsapi")
    }

    val createBtn = modal.locator("button.ant-btn-primary").filter(new Locator.FilterOptions().setHasText("Create")).first()
    Utils.waitVisible(createBtn)
    Utils.clickWithCursor(page, createBtn)

    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      val visible = try modal.isVisible() catch { case _: Exception => false }
      if (!visible) return true
      page.waitForTimeout(250)
    }
    false
  }

  private def ensureComputingUnitReadyInternal(page: Page, timeoutMs: Int): Unit = {
    val btn = runButton(page)
    Utils.waitVisible(btn)

    val startText = buttonText(btn)
    val startDisabled = isDisabled(btn)
    if (!startDisabled && !startText.equalsIgnoreCase("Connect")) return

    val prepared = createOrSelectComputingUnit(page, timeoutMs)
    if (!prepared) return

    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      val text = buttonText(btn)
      val disabled = isDisabled(btn)
      if (!disabled && !text.equalsIgnoreCase("Connect")) return
      page.waitForTimeout(500)
    }
  }

  def ensureComputingUnitReady(timeoutMs: Int = 90000): this.type = addStep(new ControllerStep {
    override def name = "Ensure Computing Unit Ready"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()
      ensureComputingUnitReadyInternal(page, timeoutMs)
    }
  })

  def runWorkflowAndWait(timeoutMs: Int = 120000): this.type = addStep(new ControllerStep {
    override def name = "Run Workflow And Wait"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val btn = runButton(page)
      Utils.waitVisible(btn)

      def waitUntilEnabled(maxWaitMs: Int): Boolean = {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
          if (!isDisabled(btn)) return true
          page.waitForTimeout(250)
        }
        !isDisabled(btn)
      }

      var initial = buttonText(btn)
      if (isDisabled(btn)) {
        if (initial.equalsIgnoreCase("Connect")) {
          ensureComputingUnitReadyInternal(page, timeoutMs = 90000)
          initial = buttonText(btn)
          if (!waitUntilEnabled(6000)) {
            println(s"[Execution] Skip run: run button stays disabled in '$initial' state.")
            return
          }
          initial = buttonText(btn)
        } else if (!waitUntilEnabled(8000)) {
          throw new RuntimeException(s"Run button is disabled. Current text: '$initial'")
        }
      }

      if (initial.equalsIgnoreCase("Connect")) {
        if (!isDisabled(btn)) {
          Utils.clickWithCursor(page, btn)
          page.waitForTimeout(700)
        }
      }

      val beforeRun = buttonText(btn)
      if (beforeRun.equalsIgnoreCase("Run")) {
        if (isDisabled(btn)) {
          println("[Execution] Skip run: 'Run' is visible but disabled.")
          return
        }
        Utils.clickWithCursor(page, btn)
      } else if (beforeRun.equalsIgnoreCase("Connect")) {
        println("[Execution] Skip run: still in 'Connect' state after connect attempt.")
        return
      }

      val start = System.currentTimeMillis()
      var seenTransition = false
      while (System.currentTimeMillis() - start < timeoutMs) {
        val text = buttonText(btn)
        val t = text.toLowerCase
        if (text.nonEmpty && text != initial) {
          seenTransition = true
        }

        if (seenTransition && t == "run") {
          page.waitForTimeout(300)
          return
        }

        if (!seenTransition && t == "run" && System.currentTimeMillis() - start > 5000) {
          return
        }

        page.waitForTimeout(400)
      }

      throw new RuntimeException(s"Workflow execution did not finish within ${timeoutMs}ms. Run button text='${buttonText(btn)}'")
    }
  })

  def openResultPanel(timeoutMs: Int = 15000): this.type = addStep(new ControllerStep {
    override def name = "Open Result Panel"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val title = page.locator("#result-container #title").first()
      def titleVisible: Boolean = {
        if (title.count() == 0) false
        else {
          try title.isVisible()
          catch { case _: Exception => false }
        }
      }
      if (title.count() > 0) {
        val visible = titleVisible
        if (visible) return
      }

      val viewResultBtn = page.getByTitle("view result")
        .or(page.getByTitle("click to remove view result")).first()
      if (viewResultBtn.count() > 0 && !isDisabled(viewResultBtn)) {
        Utils.clickWithCursor(page, viewResultBtn)
      }

      var retries = 0
      while (retries < 20 && !titleVisible) {
        page.waitForTimeout(250)
        retries += 1
      }

      if (!titleVisible) {
        val openResultBtn = page.locator("#result-buttons li[nz-menu-item]").first()
        if (openResultBtn.count() > 0) {
          Utils.clickWithCursor(page, openResultBtn)
        }
      }

      val start = System.currentTimeMillis()
      while (System.currentTimeMillis() - start < timeoutMs) {
        if (titleVisible) return
        page.waitForTimeout(200)
      }
      throw new RuntimeException("Result panel did not open in time.")
    }
  })
}
