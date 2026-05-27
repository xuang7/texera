package org.apache.texera.docs.controllers

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.microsoft.playwright._
import com.microsoft.playwright.options.{AriaRole, LoadState, WaitForSelectorState, WaitUntilState}
import org.apache.texera.amber.operator.metadata.{GroupInfo, OperatorGroupConstants, OperatorMetadataGenerator}
import org.apache.texera.docs.config.TestDataConfig
import org.apache.texera.docs.scripts.OperatorFieldValues

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

// ═══════════════════════════════════════════════════════════════════
// Shared timing / state constants
// ═══════════════════════════════════════════════════════════════════

private[controllers] object Delays {
  // 3-tier scale for `page.waitForTimeout(...)` — semantic, not literal.
  //   Tick    : DOM micro-updates between user-like actions
  //   Settle  : dropdown / collapse / toggle animation settles
  //   Long    : major UI transitions, panel collapse, drag-drop release
  //   Network : post-import / post-Run / file chooser / iceberg snapshot
  val Tick: Int = 80
  val Settle: Int = 150
  val Long: Int = 300
  val Network: Int = 700
}

private[controllers] object Timeouts {
  // Upper bounds for `Locator.waitFor(...)` and similar.
  val Quick: Int = 2000   // small visible-element waits
  val Medium: Int = 5000  // navigation / file chooser
  val Long: Int = 20000   // page load with heavy assets
}

private[controllers] object Retries {
  // Default loop iteration counts for predicate polling.
  val Short: Int = 8
  val Medium: Int = 16
  val Long: Int = 24
}

private[controllers] object RunButtonStates {
  // Texts displayed on the Run/Pause toolbar button. The actual button-state
  // string is rendered by the frontend; if any of these strings change in the
  // UI, update them here in lockstep.
  val Connect: String = "Connect"
  val Connecting: String = "Connecting"
  val Invalid: String = "Invalid Workflow"
  val Run: String = "Run"
  val Pause: String = "Pause"
}

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

  private def normalizeText(s: String): String =
    Option(s).getOrElse("").trim.toLowerCase.replaceAll("\\s+", " ")

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

  def tryFillSelect(
    page: Page,
    field: Locator,
    value: Option[String] = None,
    allowFallbackToFirst: Boolean = true
  ): Boolean = {
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
      page.waitForTimeout(Delays.Tick)
    }

    try selector.scrollIntoViewIfNeeded() catch { case _: Exception => }
    Utils.clickWithCursor(page, selector)

    // retry limit: 8
    var retries = 0
    while (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0 && retries < Retries.Short) {
      page.waitForTimeout(Delays.Tick)
      retries += 1
    }
    // Some selects need one more click to open.
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0) {
      Utils.clickWithCursor(page, selector)
      retries = 0
      while (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0 && retries < Retries.Short) {
        page.waitForTimeout(Delays.Tick)
        retries += 1
      }
    }

    val requested = value.map(_.trim).filter(_.nonEmpty)
    requested match {
      case Some(v) =>
        var chosen = false
        var attempts = 0
        val maxAttempts = 8
        while (!chosen && attempts < maxAttempts) {
          try {
            Utils.chooseDropdownOptionByText(page, v)
            chosen = true
          } catch {
            case _: Exception =>
              attempts += 1
              if (!chosen && attempts < maxAttempts) {
                // Options can arrive later (schema propagation after dataset switch).
                // Re-open and retry with increasing delay.
                try page.keyboard().press("Escape") catch { case _: Exception => }
                val delay = if (attempts <= 3) 300 else 800
                page.waitForTimeout(delay)
                Utils.clickWithCursor(page, selector)
                var retries = 0
                while (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() == 0 && retries < Retries.Short) {
                  page.waitForTimeout(Delays.Tick)
                  retries += 1
                }
                page.waitForTimeout(Delays.Settle)
              }
          }
        }

        if (!chosen) {
          if (!allowFallbackToFirst) return false
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
      page.waitForTimeout(Delays.Tick)
    }

    if (!allowFallbackToFirst && requested.nonEmpty) {
      val expected = normalizeText(requested.get)
      val selected = scope.locator(".ant-select-selection-item")
      val total = selected.count()
      var i = 0
      var matched = false
      while (i < total && !matched) {
        val text = try selected.nth(i).innerText() catch { case _: Exception => "" }
        val actual = normalizeText(text)
        if (actual.nonEmpty && (actual.contains(expected) || expected.contains(actual))) matched = true
        i += 1
      }
      if (!matched) return false
    }
    true
  }

  def tryFillSelectMany(page: Page, field: Locator, values: Seq[String]): Boolean = {
    val clean = values.map(_.trim).filter(_.nonEmpty)
    if (clean.isEmpty) return true
    clean.foreach { v =>
      if (!tryFillSelect(page, field, Some(v))) return false
      page.waitForTimeout(Delays.Tick)
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
        page.waitForTimeout(Delays.Tick)
      }
      selected = selectedNow
    }

    clean.forall(v => selected.contains(norm(v)))
  }

  // Enter given value. If the input already holds the same value, no-op; otherwise overwrite.
  def tryFillText(page: Page, field: Locator, value: String): Boolean = {
    val scope = fieldScope(field)
    val input = firstVisible(
      scope.locator("textarea, input:not([type='checkbox']):not([type='radio']):not([readonly])")
    ).orElse {
      firstVisible(field.locator("textarea, input:not([type='checkbox']):not([type='radio']):not([readonly])"))
    }.orNull
    if (input == null || input.count() == 0) return false

    val current = try Option(input.inputValue()).getOrElse("") catch { case _: Exception => "" }
    if (current.trim == value.trim) return true

    focusField(page, field)
    Utils.clickWithCursor(page, input)
    input.fill(value)
    true
  }

  // check box -> select (delegates to trySetBoolean with target=true)
  def tryCheckCheckbox(page: Page, field: Locator): Boolean =
    trySetBoolean(page, field, target = true)

  def trySetBoolean(page: Page, field: Locator, target: Boolean): Boolean = {
    val scope = fieldScope(field)

    def readCheckboxState(container: Locator): Option[Boolean] = {
      val hasCheckbox =
        container.locator("input[type='checkbox'], .ant-checkbox, .ant-checkbox-wrapper").count() > 0
      if (!hasCheckbox) return None

      val cssChecked = container.locator("input[type='checkbox']:checked").count() > 0
      val antChecked = container.locator(".ant-checkbox-checked, .ant-checkbox-wrapper-checked").count() > 0
      val ariaChecked = container.locator("[aria-checked='true']").count() > 0
      Some(cssChecked || antChecked || ariaChecked)
    }

    val checkboxState = readCheckboxState(scope).orElse(readCheckboxState(field))
    if (checkboxState.nonEmpty) {
      val clickTarget = firstVisible(
        scope.locator(".ant-checkbox-wrapper, .ant-checkbox, input[type='checkbox']")
      ).orElse(
        firstVisible(field.locator(".ant-checkbox-wrapper, .ant-checkbox, input[type='checkbox']"))
      ).orNull

      if (clickTarget != null && checkboxState.get != target) {
        Utils.clickWithCursor(page, clickTarget)
        page.waitForTimeout(Delays.Tick)
      }

      val verify = readCheckboxState(scope).orElse(readCheckboxState(field))
      if (clickTarget != null && verify.exists(_ != target)) {
        Utils.clickWithCursor(page, clickTarget)
        page.waitForTimeout(Delays.Tick)
      }
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

private[controllers] object LLMFormRecovery {
  private case class BrowserAction(
    action: String,
    label: String,
    value: String,
    text: String,
    waitMs: Int
  )

  private val mapper = new ObjectMapper()
  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  private def envFlag(name: String, default: Boolean = false): Boolean = {
    sys.env.get(name).map(_.trim.toLowerCase) match {
      case Some("1" | "true" | "yes" | "on") => true
      case Some("0" | "false" | "no" | "off") => false
      case _ => default
    }
  }

  private def enabled: Boolean = envFlag("DOCS_LLM_FORM_ENABLE", default = false)
  private def apiKey: String = sys.env.getOrElse("OPENAI_API_KEY", "").trim
  private def model: String = sys.env.getOrElse("OPENAI_MODEL", "gpt-4o-mini").trim
  private def baseUrl: String = sys.env.getOrElse("OPENAI_BASE_URL", "https://api.openai.com/v1").trim.stripSuffix("/")

  private def normalizeText(s: String): String =
    Option(s).getOrElse("").trim.toLowerCase.replaceAll("\\s+", " ")

  private def propertyRoot(page: Page): Locator = {
    val root = page.locator("#property-editor").first()
    if (root.count() > 0) root else page.locator("body").first()
  }

  private def formSummary(root: Locator, limit: Int = 24): String = {
    val fields = root.locator("formly-field")
    val total = fields.count()
    val lines = scala.collection.mutable.ArrayBuffer.empty[String]
    var i = 0
    while (i < total && lines.size < limit) {
      val f = fields.nth(i)
      val visible = try f.isVisible() catch { case _: Exception => false }
      if (visible) {
        val label = try {
          val l = f.locator("label").first()
          if (l.count() > 0) Option(l.innerText()).getOrElse("").trim else ""
        } catch { case _: Exception => "" }
        if (label.nonEmpty) {
          val control =
            if (f.locator("nz-select, .ant-select, [role='combobox']").count() > 0) "select"
            else if (f.locator("textarea, input:not([type='checkbox']):not([type='radio'])").count() > 0) "text"
            else if (f.locator("input[type='checkbox'], .ant-switch, button[role='switch']").count() > 0) "boolean"
            else "unknown"
          lines += s"""- label="$label", control="$control""""
        }
      }
      i += 1
    }
    if (lines.nonEmpty) lines.mkString("\n") else "- no visible form fields found"
  }

  private def matchingFields(root: Locator, label: String): Seq[Locator] = {
    val target = normalizeText(label)
    if (target.isEmpty) return Seq.empty
    val fields = root.locator("formly-field")
    val total = fields.count()
    val out = scala.collection.mutable.ArrayBuffer.empty[Locator]
    var i = 0
    while (i < total) {
      val f = fields.nth(i)
      val visible = try f.isVisible() catch { case _: Exception => false }
      if (visible) {
        val labels = f.locator("label")
        val n = labels.count()
        var j = 0
        var matched = false
        while (j < n && !matched) {
          val txt = try Option(labels.nth(j).innerText()).getOrElse("").trim
          catch { case _: Exception => "" }
          val norm = normalizeText(txt)
          if (norm == target || norm.contains(target) || target.contains(norm)) matched = true
          j += 1
        }
        if (matched) out += f
      }
      i += 1
    }
    out.toSeq
  }

  private def fieldContainsValue(field: Locator, expected: String): Boolean = {
    val expectedNorm = normalizeText(expected)
    if (expectedNorm.isEmpty) return false

    val selected = field.locator(".ant-select-selection-item")
    val selectedCount = selected.count()
    var i = 0
    while (i < selectedCount) {
      val txt = try Option(selected.nth(i).innerText()).getOrElse("")
      catch { case _: Exception => "" }
      val norm = normalizeText(txt)
      if (norm.nonEmpty && (norm == expectedNorm || norm.contains(expectedNorm) || expectedNorm.contains(norm))) return true
      i += 1
    }

    val inputs = field.locator("textarea, input:not([type='checkbox']):not([type='radio'])")
    val inputCount = inputs.count()
    var j = 0
    while (j < inputCount) {
      val v = try Option(inputs.nth(j).inputValue()).getOrElse("")
      catch { case _: Exception => "" }
      val norm = normalizeText(v)
      if (norm.nonEmpty && (norm == expectedNorm || norm.contains(expectedNorm) || expectedNorm.contains(norm))) return true
      j += 1
    }
    false
  }

  private def verifyByLabels(root: Locator, labels: Seq[String], expectedValue: String): Boolean = {
    labels.exists { l =>
      matchingFields(root, l).exists(fieldContainsValue(_, expectedValue))
    }
  }

  private def extractMessageContent(responseBody: String): Option[String] = {
    try {
      val node = mapper.readTree(responseBody)
      val text = node.path("choices").path(0).path("message").path("content").asText("")
      Option(text).map(_.trim).filter(_.nonEmpty)
    } catch {
      case _: Exception => None
    }
  }

  private def extractJsonBlock(raw: String): String = {
    val trimmed = raw.trim
    val unfenced =
      if (trimmed.startsWith("```")) {
        trimmed
          .replaceFirst("^```[a-zA-Z0-9_-]*\\n?", "")
          .replaceFirst("\\n?```$", "")
          .trim
      } else trimmed
    val start = unfenced.indexOf('{')
    val end = unfenced.lastIndexOf('}')
    if (start >= 0 && end > start) unfenced.substring(start, end + 1) else unfenced
  }

  private def parseActions(raw: String): Seq[BrowserAction] = {
    try {
      val json = extractJsonBlock(raw)
      val root = mapper.readTree(json)
      val actions = root.path("actions")
      if (!actions.isArray) return Seq.empty
      actions.elements().asScala.toSeq.map { n =>
        BrowserAction(
          action = n.path("action").asText("").trim.toLowerCase.replace("-", "_"),
          label = n.path("label").asText("").trim,
          value = n.path("value").asText("").trim,
          text = n.path("text").asText("").trim,
          waitMs = n.path("waitMs").asInt(0)
        )
      }.filter(_.action.nonEmpty)
    } catch {
      case _: Exception => Seq.empty
    }
  }

  private def requestActions(prompt: String): Seq[BrowserAction] = {
    if (!enabled) return Seq.empty
    if (apiKey.isEmpty) {
      println("  [LLM] OPENAI_API_KEY is empty; skip LLM recovery")
      return Seq.empty
    }

    val body = mapper.createObjectNode()
    body.put("model", model)
    body.put("temperature", 0.0)
    body.put("max_tokens", 500)

    val messages = mapper.createArrayNode()
    val system = mapper.createObjectNode()
    system.put("role", "system")
    system.put(
      "content",
      "Return JSON only: {\"actions\":[...]} with allowed actions: click_plus, select_by_label, fill_text_by_label, click_by_text, scroll_label, wait. Do not include explanations."
    )
    val user = mapper.createObjectNode()
    user.put("role", "user")
    user.put("content", prompt)
    messages.add(system)
    messages.add(user)
    body.set("messages", messages)

    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/chat/completions"))
      .timeout(Duration.ofSeconds(20))
      .header("Authorization", s"Bearer $apiKey")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()

    try {
      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 300) {
        println(s"  [LLM] request failed: HTTP ${resp.statusCode()}")
        return Seq.empty
      }
      extractMessageContent(resp.body()).map(parseActions).getOrElse(Seq.empty)
    } catch {
      case e: Exception =>
        println(s"  [LLM] request error: ${e.getMessage}")
        Seq.empty
    }
  }

  private def fieldByLabel(root: Locator, label: String): Locator = {
    val fields = matchingFields(root, label)
    if (fields.nonEmpty) fields.last else root.locator("__not_found__").first()
  }

  private def clickPlusNearLabel(page: Page, root: Locator, label: String): Boolean = {
    val anchor = root.getByText(label, new Locator.GetByTextOptions().setExact(false)).first()
    if (anchor.count() == 0) return false
    val scope = anchor.locator("xpath=ancestor::*[self::formly-field or self::div][1]").first()
    val plus = scope.locator(
      "button:has(i.anticon-plus), button.ant-btn-circle, .ant-btn:has-text('Add'), button[aria-label='add']"
    ).last()
    if (plus.count() == 0) return false
    Utils.clickWithCursor(page, plus)
    page.waitForTimeout(Delays.Settle)
    true
  }

  private def selectByLabel(page: Page, root: Locator, label: String, value: String): Boolean = {
    val field = fieldByLabel(root, label)
    if (field.count() == 0) return false
    FormHelpers.tryFillSelect(page, field, Some(value), allowFallbackToFirst = false)
  }

  private def fillTextByLabel(page: Page, root: Locator, label: String, value: String): Boolean = {
    val field = fieldByLabel(root, label)
    if (field.count() == 0) return false
    FormHelpers.tryFillText(page, field, value)
  }

  private def executeActions(page: Page, root: Locator, actions: Seq[BrowserAction]): Unit = {
    actions.take(6).foreach { a =>
      a.action match {
        case "click_plus" =>
          if (a.label.nonEmpty) clickPlusNearLabel(page, root, a.label)
        case "select_by_label" =>
          if (a.label.nonEmpty && a.value.nonEmpty) selectByLabel(page, root, a.label, a.value)
        case "fill_text_by_label" =>
          if (a.label.nonEmpty && a.value.nonEmpty) fillTextByLabel(page, root, a.label, a.value)
        case "click_by_text" =>
          if (a.text.nonEmpty) {
            val t = root.getByText(a.text, new Locator.GetByTextOptions().setExact(false)).first()
            if (t.count() > 0) Utils.clickWithCursor(page, t)
          }
        case "scroll_label" =>
          if (a.label.nonEmpty) {
            val t = root.getByText(a.label, new Locator.GetByTextOptions().setExact(false)).first()
            if (t.count() > 0) {
              try t.scrollIntoViewIfNeeded() catch { case _: Exception => }
            }
          }
        case "wait" =>
          if (a.waitMs > 0) page.waitForTimeout(a.waitMs)
        case _ =>
      }
      if (a.waitMs > 0 && a.action != "wait") page.waitForTimeout(a.waitMs)
    }
  }

  def tryRecoverSingleField(
    page: Page,
    fieldKey: String,
    labelHints: Seq[String],
    expectedValue: String
  ): Boolean = {
    if (!enabled) return false
    val root = propertyRoot(page)
    if (verifyByLabels(root, labelHints, expectedValue)) return true

    val prompt =
      s"""Form fill failed for scalar field.
         |fieldKey: $fieldKey
         |labelHints: ${labelHints.mkString(", ")}
         |expectedValue: $expectedValue
         |
         |Current visible form summary:
         |${formSummary(root)}
         |
         |Output strict JSON:
         |{"actions":[{"action":"select_by_label","label":"...","value":"..."}]}
         |""".stripMargin

    val actions = requestActions(prompt)
    if (actions.isEmpty) return false
    println(s"  [LLM] trying ${actions.size} recovery actions for field '$fieldKey'")
    executeActions(page, root, actions)
    verifyByLabels(root, labelHints, expectedValue)
  }

  def tryRecoverArraySubField(
    page: Page,
    arrayFieldKey: String,
    arrayLabelHints: Seq[String],
    subFieldKey: String,
    subFieldLabelHints: Seq[String],
    expectedValue: String
  ): Boolean = {
    if (!enabled) return false
    val root = propertyRoot(page)
    if (verifyByLabels(root, subFieldLabelHints, expectedValue)) return true

    val prompt =
      s"""Form fill failed for array sub-field.
         |arrayFieldKey: $arrayFieldKey
         |arrayLabelHints: ${arrayLabelHints.mkString(", ")}
         |subFieldKey: $subFieldKey
         |subFieldLabelHints: ${subFieldLabelHints.mkString(", ")}
         |expectedValue: $expectedValue
         |
         |Current visible form summary:
         |${formSummary(root)}
         |
         |Use click_plus before fill when needed.
         |Output strict JSON:
         |{"actions":[{"action":"click_plus","label":"Add attribute"},{"action":"select_by_label","label":"Attribute Name","value":"$expectedValue"}]}
         |""".stripMargin

    val actions = requestActions(prompt)
    if (actions.isEmpty) return false
    println(s"  [LLM] trying ${actions.size} recovery actions for array '$arrayFieldKey.$subFieldKey'")
    executeActions(page, root, actions)
    verifyByLabels(root, subFieldLabelHints, expectedValue)
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
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(Timeouts.Medium))
      } catch {
        case _: Exception =>
      }
      page.waitForTimeout(Delays.Long)
      ctx.ensureFakeCursor()

      val loginSubmit = page.getByTestId("login-submit")
      println(s"[Login] login-submit count = ${loginSubmit.count()}, url = ${page.url()}")

      // login element is not seen, page is loading
      if (loginSubmit.count() == 0) {
        try {
          loginSubmit.first().waitFor(
            new Locator.WaitForOptions()
              .setState(WaitForSelectorState.VISIBLE)
              .setTimeout(Timeouts.Long)
          )
        } catch {
          case _: Exception =>
            val title = try page.title() catch { case _: Exception => "<no title>" }
            val localLoginCount = page.locator("texera-local-login").count()
            val anyInputCount = page.locator("input").count()
            val signInBtnCount = page.locator("button:has-text('Sign in')").count()
            val aboutTitleCount = page.locator("h1.about-title").count()
            val bodyText = try {
              val raw = page.locator("body").innerText()
              if (raw.length > 400) raw.substring(0, 400) + "..." else raw
            } catch { case _: Exception => "<no body>" }
            val shotPath = java.nio.file.Paths.get(
              TestDataConfig.videoOutputDir, "debug_login_failed.png"
            )
            val screenshotSaved =
              try {
                java.nio.file.Files.createDirectories(shotPath.getParent)
                page.screenshot(new Page.ScreenshotOptions().setPath(shotPath).setFullPage(true))
                true
              } catch { case _: Exception => false }
            println(s"[Login][debug] title=$title")
            println(s"[Login][debug] texera-local-login count=$localLoginCount, input count=$anyInputCount, signIn btn count=$signInBtnCount, about-title count=$aboutTitleCount")
            println(s"[Login][debug] body text (first 400 chars): $bodyText")
            if (screenshotSaved) println(s"[Login][debug] screenshot saved: $shotPath")
            else println(s"[Login][debug] screenshot capture failed (target: $shotPath)")
            throw new RuntimeException(s"Login page not visible. URL: ${page.url()}")
        }
      }

      val usernameField = page.getByTestId("login-username")
        .or(page.getByPlaceholder("Username")).first()
      usernameField.waitFor(
        // Wait for text box visible
        new Locator.WaitForOptions()
          .setState(WaitForSelectorState.VISIBLE)
          .setTimeout(Timeouts.Medium)
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
          .setTimeout(Timeouts.Medium)
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
      page.waitForTimeout(Delays.Network)

      // If still on login page, try pressing Enter once.
      if (page.getByTestId("login-submit").count() > 0) {
        passwordField.press("Enter")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.waitForTimeout(Delays.Network)
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
        page.waitForTimeout(Delays.Long)
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
          .setTimeout(Timeouts.Long)
      )
      try {
        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(Timeouts.Medium))
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

      // Always navigate to the workflow listing page (where the "Create Workflow"
      // button lives) and create a fresh workflow. Going to /dashboard root would
      // land on the about/home page; the Create button is on /dashboard/user/workflow.
      // (Previously this short-circuited when a workflow was already open,
      //  which let stale canvas state leak between scenarios.)
      page.navigate(
        s"${TestDataConfig.baseUrl}/dashboard/user/workflow",
        new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
          .setTimeout(Timeouts.Long)
      )

      val createBtn = page.getByTestId("navigation-create-workflow-button")
        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create Workflow")))
        .or(page.getByText("Create Workflow", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(createBtn)
      Utils.clickWithCursor(page, createBtn)

      try {
        page.getByTestId("navigation-workflow-canvas").first()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Long))
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

      // Defensive: clear any pre-existing operators on the canvas before importing.
      // Otherwise the import merges with stale state and dragNextTo("Split-operator-")
      // can latch onto the wrong (old) Split node.
      val staleCount = page.locator("g.joint-cell.joint-element").count()
      if (staleCount > 0) {
        val deleteAllBtn = page.getByTitle("delete all").first()
        val deleteAllVisible =
          deleteAllBtn.count() > 0 && (try deleteAllBtn.isVisible() catch { case _: Exception => false })
        if (deleteAllVisible) {
          Utils.clickWithCursor(page, deleteAllBtn)
          Utils.pollUntil(2000, 100) {
            page.locator("g.joint-cell.joint-element").count() == 0
          }
        }
      }

      val beforeCount = page.locator("g.joint-cell.joint-element").count()

      val importBtn = page.getByTitle("import workflow")
        .or(page.getByTitle("import"))
        .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("import")))
        .first()
      Utils.waitVisible(importBtn)

      val chooser = page.waitForFileChooser(new Page.WaitForFileChooserOptions().setTimeout(Timeouts.Medium), () => {
        Utils.clickWithCursor(page, importBtn)
      })
      chooser.setFiles(filePath) // hide the file selection window

      var retries = 0
      while (page.locator("g.joint-cell.joint-element").count() <= beforeCount && retries < Retries.Long) {
        page.waitForTimeout(Delays.Settle)
        retries += 1
      } // Check import finish, # operator increase

      // Centralize all operator automatically
      val centerBtn = page.getByTitle("minimap-center-button")
      if (centerBtn.count() > 0) {
        Utils.clickWithCursor(page, centerBtn)
        page.waitForTimeout(Delays.Tick)
      }

      val afterCount = page.locator("g.joint-cell.joint-element").count()
      if (afterCount <= beforeCount) {
        println(s"[Import] No new operators after import (before=$beforeCount, after=$afterCount)")
      } else {
        println(s"[Import] Loaded ${afterCount - beforeCount} operators from ${jsonFilePath.split("/").last}")
      }
      page.waitForTimeout(Delays.Tick)
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
        page.waitForTimeout(Delays.Long)
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
        panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Quick))
      } catch {
        case _: Exception =>
          // Some states require one more click to switch to the Operators tab.
          Utils.clickWithCursor(page, operatorsMenu)
          panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Quick))
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
      while (page.locator("g.joint-cell.joint-element").count() < targetCount && retries < Retries.Medium) {
        page.waitForTimeout(Delays.Settle)
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
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Quick))
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
      page.waitForTimeout(Delays.Settle)

      val afterBox = Utils.cellBox(cur)
      if (afterBox != null && (Utils.overlaps(prevBox, afterBox) || Utils.centerDx(prevBox, afterBox) < spacing * 0.8)) {
        Utils.nudgeCell(page, cur, dx + spacing, 0)
        page.waitForTimeout(Delays.Settle)
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
                     connectAdditionalToInputIndex: Option[Int] = None,
                     // Horizontal gap (screen px) between anchor's right edge and the
                     // dropped operator's left edge. Default preserves the prior
                     // visualization / data-cleaning behavior; ML scripts override with
                     // a tighter value because their template has more intermediate nodes.
                     dragSpacing: Double = 180.0
                   ): this.type = addStep(new ControllerStep {
    override def name = s"Insert '$operatorName' via Drag${dragNextTo.map(n => s" (next to $n)").getOrElse("")}"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()
      // Record current operator for downstream builders' hint lookups.
      operatorType.filter(_.nonEmpty).foreach(t => ctx.currentOperatorType = Some(t))

      // Operator search text override from _controllerHints.operatorSidebarText.
      val sidebarSearchText: String = operatorType
        .flatMap(OperatorFieldValues.operatorSidebarText)
        .getOrElse(operatorName)

      // ── Open operator panel & resolve source ──
      val operatorsMenu = page.getByTestId("operator-left-panel-operators-button")
        .or(page.getByText("Operators", new Page.GetByTextOptions().setExact(true))).first()
      Utils.waitVisible(operatorsMenu)
      Utils.clickWithCursor(page, operatorsMenu)

      val panelSearch = page.getByTestId("operator-search-input")
        .or(page.getByPlaceholder("search operator")).first()
      try {
        panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Quick))
      } catch {
        case _: Exception =>
          // Some states require one more click to switch to the Operators tab.
          Utils.clickWithCursor(page, operatorsMenu)
          panelSearch.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Quick))
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
        searchInput.fill(sidebarSearchText)
        page.waitForTimeout(Delays.Settle)
        dragHandle(resolveOperatorSource(page, sidebarSearchText, operatorType))
      }
      operator.scrollIntoViewIfNeeded()
      page.waitForTimeout(Delays.Tick)

      // ── Prepare canvas ──
      val canvas = page.getByTestId("navigation-workflow-canvas")
        .or(page.locator("svg[joint-selector='svg'], svg#v-2")).first()
      Utils.waitVisible(canvas)
      canvas.scrollIntoViewIfNeeded()
      page.waitForTimeout(Delays.Tick)

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
          Some((
            math.min(canvasBox.x + canvasBox.width - 30, box.x + box.width + dragSpacing),
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
        page.waitForTimeout(Delays.Tick)
        val retryOperator = dragHandle(resolveOperatorSource(page, sidebarSearchText, operatorType))
        performDrag(page, retryOperator, tgtX, tgtY)
      }
      if (!waitForNodeCountAtLeast(page, targetCount, maxRetries = 20)) {
        // Last fallback: insert through search + Enter when drag source is flaky.
        val searchInput = page.getByTestId("operator-search-input")
          .or(page.getByPlaceholder("search operator")).first()
        Utils.waitVisible(searchInput)
        Utils.clickWithCursor(page, searchInput)
        searchInput.fill("")
        page.waitForTimeout(Delays.Tick)
        searchInput.fill(sidebarSearchText)
        page.waitForTimeout(Delays.Tick)
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
        page.waitForTimeout(Delays.Tick)
      }

      // ── Click the new node to select it ──
      val newNode = operatorType.flatMap(findNodeByType(page, _))
        .getOrElse(Utils.waitVisible(page.locator("g.joint-cell.joint-element").nth(beforeCount)))
      if (newNode.count() > 0) {
        val body = newNode.locator("rect.body").first()
        if (body.count() > 0) Utils.clickWithCursor(page, body) else Utils.clickWithCursor(page, newNode)
      }

      if (autoConnectToAnchor && dragNextTo.isDefined && anchorNode.exists(_.count() > 0) && newNode.count() > 0) {
        // Check if the drag itself already created a link (canvas port-snapping)
        val currentLinkCount = page.locator("g.joint-cell.joint-link").count()
        if (currentLinkCount > beforeLinkCount) {
          println(s"[Operator] Drag already created a link for '$operatorName', skipping autoConnect")
        } else {
          val connected = tryAutoConnect(
            page,
            anchorNode.get,
            newNode,
            fromPortIndex = fromPortIndex,
            toPortIndex = toPortIndex
          )
          if (connected) {
            var retries = 0
            while (page.locator("g.joint-cell.joint-link").count() <= beforeLinkCount && retries < Retries.Short) {
              page.waitForTimeout(Delays.Tick)
              retries += 1
            }
            if (page.locator("g.joint-cell.joint-link").count() <= beforeLinkCount) {
              println(s"[Operator] Warning: explicit connect attempted but no new link was detected for '$operatorName'")
            }
          } else {
            println(s"[Operator] Warning: could not locate connectable ports for '$operatorName'")
          }
        }
      }

      if (connectAdditionalFrom.isDefined && newNode.count() > 0) {
        // Clear state from the first connection before attempting the second
        try page.keyboard().press("Escape") catch { case _: Exception => }
        page.waitForTimeout(Delays.Settle)

        val additionalFromNode = findNodeByType(page, connectAdditionalFrom.get)
        if (additionalFromNode.isEmpty) {
          println(s"[Operator] Warning: connectAdditionalFrom='${connectAdditionalFrom.get}' not found on canvas")
        } else {
          val expectedLinkCount = page.locator("g.joint-cell.joint-link").count() + 1
          val targetInputPort = connectAdditionalToInputIndex.getOrElse(0)

          val inputPorts = collectInputPortCount(page, newNode)
          val alreadyConnected = inputPorts > 0 && page.locator("g.joint-cell.joint-link").count() >= expectedLinkCount
          if (alreadyConnected) {
            println(s"[Operator] Additional input port likely already connected for '$operatorName', skipping")
          } else {
            try additionalFromNode.get.scrollIntoViewIfNeeded() catch { case _: Exception => }
            try newNode.scrollIntoViewIfNeeded() catch { case _: Exception => }
            page.waitForTimeout(Delays.Tick)

            val beforeExtraLinkCount = page.locator("g.joint-cell.joint-link").count()
            var connected = tryAutoConnect(
              page,
              additionalFromNode.get,
              newNode,
              fromPortIndex = connectAdditionalFromPortIndex,
              toPortIndex = targetInputPort
            )
            if (!connected) {
              page.waitForTimeout(Delays.Network)
              try page.keyboard().press("Escape") catch { case _: Exception => }
              page.waitForTimeout(Delays.Settle)
              connected = tryAutoConnect(
                page,
                additionalFromNode.get,
                newNode,
                fromPortIndex = connectAdditionalFromPortIndex,
                toPortIndex = targetInputPort
              )
            }
            if (connected) {
              var retries = 0
              while (page.locator("g.joint-cell.joint-link").count() <= beforeExtraLinkCount && retries < Retries.Short) {
                page.waitForTimeout(Delays.Tick)
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
      }

      // ── Reposition only for default placement mode.
      // Fallback reposition when no dragNextTo anchor was given but the canvas
      // already has nodes (e.g. defensive drop): nudge near the last node with
      // the original 220px gap. `dragSpacing` only applies to the drag-drop step
      // above, which is where the script-specified anchor lives.
      if (dragNextTo.isEmpty) {
        val referenceNode = anchorNode
          .filter(_.count() > 0)
          .orElse(if (beforeCount > 0) Some(page.locator("g.joint-cell.joint-element").nth(beforeCount - 1)) else None)
          .orNull

        if (referenceNode != null && referenceNode.count() > 0) {
          val refBox = Utils.cellBox(referenceNode)
          val newBox = Utils.cellBox(newNode)
          if (refBox != null && newBox != null) {
            val targetCenterX = refBox.x + refBox.width + 220.0 + newBox.width / 2.0
            val targetCenterY = refBox.y + refBox.height / 2.0
            val currentCenterX = newBox.x + newBox.width / 2.0
            val currentCenterY = newBox.y + newBox.height / 2.0
            Utils.nudgeCell(page, newNode, targetCenterX - currentCenterX, targetCenterY - currentCenterY)
            page.waitForTimeout(Delays.Tick)
          }
          Utils.ensureSeparated(page, referenceNode, newNode)
        }
      }
    }
  })

  def selectOperatorOnCanvas(operatorTypeOrName: String): this.type = addStep(new ControllerStep {
    override def name = s"Select Operator '$operatorTypeOrName' On Canvas"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      val node = findNodeByType(page, operatorTypeOrName)
        .getOrElse(throw new RuntimeException(s"Node not found on canvas: $operatorTypeOrName"))
      val body = node.locator("rect.body").first()
      if (body.count() > 0) Utils.clickWithCursor(page, body)
      else Utils.clickWithCursor(page, node)
      page.waitForTimeout(Delays.Tick)
    }
  })

  private def findNodeByType(page: Page, operatorTypeOrName: String): Option[Locator] = {
    val normalized = normalize(operatorTypeOrName)
    val relaxed = normalized.replace("operator", "")

    // Prefer startsWith over contains to avoid "csvfilescan" matching "csvoldfilescan"
    def matches(candidate: String): Boolean = {
      val c = normalize(candidate)
      c.nonEmpty && (
        c.startsWith(normalized) ||
          c.contains(normalized + "-") ||
          c.contains(normalized) ||
          (relaxed.nonEmpty && c.startsWith(relaxed))
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

  private def collectInputPortCount(page: Page, node: Locator): Int = {
    val selector = Seq(
      "[port-group='input'][port]", "[port-group='in'][port]",
      "[port*='input']", "circle[port-group='input']"
    ).mkString(", ")
    node.locator(selector).count()
  }

  private def waitForNodeCountAtLeast(page: Page, targetCount: Int, maxRetries: Int): Boolean = {
    var retries = 0
    while (page.locator("g.joint-cell.joint-element").count() < targetCount && retries < maxRetries) {
      page.waitForTimeout(Delays.Tick)
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
      val selectors = if (io == "output") Seq("output") else Seq("input", "in")
      val selector = selectors.map(p => s"[port*='$p']").mkString(", ")

      val ports = node.locator(selector)
      val total = ports.count()
      println(s"[collectPortCenters] io=$io, selector=$selector, total=$total")
      if (total == 0) {
        val testId = try Option(node.getAttribute("data-testid")).getOrElse("") catch { case _: Exception => "" }
        val modelId = try Option(node.getAttribute("model-id")).getOrElse("") catch { case _: Exception => "" }
        println(s"[collectPortCenters]   node testId=$testId modelId=$modelId")
        val allChildren = node.locator("*")
        val childCount = allChildren.count()
        val portAttrs = (0 until Math.min(childCount, 80)).flatMap { j =>
          try {
            val el = allChildren.nth(j)
            val p = Option(el.getAttribute("port")).filter(_.nonEmpty)
            val pg = Option(el.getAttribute("port-group")).filter(_.nonEmpty)
            val tag = try Option(el.evaluate("el => el.tagName").toString).getOrElse("") catch { case _: Exception => "" }
            if (p.isDefined || pg.isDefined) Some(s"<$tag port=${p.getOrElse("")} port-group=${pg.getOrElse("")}>") else None
          } catch { case _: Exception => None }
        }
        println(s"[collectPortCenters]   all port attrs in node ($childCount children): ${portAttrs.mkString(", ")}")
      }
      var i = 0
      val seenIndices = scala.collection.mutable.Set.empty[Int]
      val buf = scala.collection.mutable.ArrayBuffer.empty[(Option[Int], Double, Double)]
      while (i < total) {
        val p = ports.nth(i)
        try {
          val visible = p.isVisible()
          val rawAttr = Option(p.getAttribute("port"))
            .orElse(Option(p.getAttribute("data-port")))
            .getOrElse("")
          println(s"[collectPortCenters]   [$i] visible=$visible, port-attr='$rawAttr'")
          if (visible) {
            val attr = rawAttr
            val idx = (".*?(?:input|output|in|out)-([0-9]+).*").r
              .findFirstMatchIn(attr)
              .map(_.group(1).toInt)
            if (!idx.exists(seenIndices.contains)) {
              // Drill down to circle.port-body for precise bounding box;
              // the wrapper <g> includes the label text and gives an offset center.
              val portBody = p.locator("circle.port-body, circle").first()
              val target =
                if (portBody.count() > 0 && (try portBody.isVisible() catch { case _: Exception => false }))
                  portBody
                else p
              val b = target.boundingBox()
              if (b != null) {
                idx.foreach(seenIndices.add)
                buf += ((idx, b.x + b.width / 2.0, b.y + b.height / 2.0))
              }
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

    // Brief pause to let the DOM settle (important when called after another connection)
    page.waitForTimeout(Delays.Tick)

    val fromPort = pickCenter(collectPortCenters(fromNode, "output"), fromPortIndex)
    val toPort = pickCenter(collectPortCenters(toNode, "input"), toPortIndex)

    val beforeLinks = page.locator("g.joint-cell.joint-link").count()

    if (fromPort.isEmpty || toPort.isEmpty) {
      println(s"[tryAutoConnect] Port not found: fromPort=$fromPort toPort=$toPort (fromIdx=$fromPortIndex toIdx=$toPortIndex)")

      // If a specific non-zero port index was requested, do NOT fall back to a
      // center-to-center drag — that would route the link to whichever port is
      // visible (typically port 0), silently aliasing two connections onto the
      // same port. Common case: HashJoin's port-1 has `dependencies=List(port-0)`
      // and is hidden until port-0 has schema. Returning false lets the caller's
      // retry loop wait and re-collect ports.
      if (fromPortIndex > 0 || toPortIndex > 0) return false

      val fromBox = Utils.cellBox(fromNode)
      val toBox = Utils.cellBox(toNode)
      if (fromBox == null || toBox == null) return false
      val startX = fromBox.x + fromBox.width - 2
      val startY = fromBox.y + fromBox.height / 2.0
      val endX = toBox.x + 2
      val endY = toBox.y + toBox.height / 2.0
      page.mouse().move(startX, startY, new Mouse.MoveOptions().setSteps(12))
      page.mouse().down()
      page.mouse().move(endX, endY, new Mouse.MoveOptions().setSteps(20))
      page.mouse().up()
      page.waitForTimeout(Delays.Settle)
      return page.locator("g.joint-cell.joint-link").count() > beforeLinks
    }

    page.mouse().move(fromPort.get._1, fromPort.get._2, new Mouse.MoveOptions().setSteps(12))
    page.mouse().down()
    page.waitForTimeout(Delays.Tick)
    page.mouse().move(toPort.get._1, toPort.get._2, new Mouse.MoveOptions().setSteps(25))
    page.mouse().up()
    page.waitForTimeout(Delays.Settle)
    page.locator("g.joint-cell.joint-link").count() > beforeLinks
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

      // Clear any active selection/interaction state from previous operations
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(Delays.Settle)

      val fromNode = findNodeByType(page, fromOperator)
        .getOrElse(throw new RuntimeException(s"Node not found: $fromOperator"))
      val toNode = findNodeByType(page, toOperator)
        .getOrElse(throw new RuntimeException(s"Node not found: $toOperator"))

      // Ensure both nodes are visible before attempting port-level drag
      try fromNode.scrollIntoViewIfNeeded() catch { case _: Exception => }
      try toNode.scrollIntoViewIfNeeded() catch { case _: Exception => }
      page.waitForTimeout(Delays.Tick)

      if (!tryAutoConnect(page, fromNode, toNode, fromPortIndex = fromPortIndex, toPortIndex = toPortIndex)) {
        // Retry once after a longer stabilization pause
        page.waitForTimeout(Delays.Network)
        try page.keyboard().press("Escape") catch { case _: Exception => }
        page.waitForTimeout(Delays.Settle)
        if (!tryAutoConnect(page, fromNode, toNode, fromPortIndex = fromPortIndex, toPortIndex = toPortIndex)) {
          throw new RuntimeException(
            s"Failed to connect $fromOperator:$fromPortIndex -> $toOperator:$toPortIndex"
          )
        }
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
          page.waitForTimeout(Delays.Tick)
          val afterClass = Option(panel.getAttribute("class")).getOrElse("")
          if (!afterClass.contains("ant-collapse-item-active")) {
            clickGroupHeader(page, header)
            page.waitForTimeout(Delays.Tick)
          }
        }
        scope = panel
      } else {
        // Fallback for non-collapse style groups.
        clickGroupHeader(page, header)
        page.waitForTimeout(Delays.Tick)
        scope = leftPanel
      }
    }

    operatorType.filter(_.nonEmpty).foreach { t =>
      val candidate = scope.getByTestId(s"operator-item-$t").first()
      if (candidate.count() > 0) {
        try candidate.scrollIntoViewIfNeeded() catch { case _: Exception => }
        page.waitForTimeout(Delays.Tick)
        return Some(candidate)
      }
    }

    val exact = scope.getByText(operatorName, new Locator.GetByTextOptions().setExact(true)).first()
    if (exact.count() == 0) return None
    val row = exact.locator("xpath=ancestor-or-self::*[@data-testid and starts-with(@data-testid,'operator-item-')][1]").first()
    val resolved = if (row.count() > 0) row else exact
    try resolved.scrollIntoViewIfNeeded() catch { case _: Exception => }
    page.waitForTimeout(Delays.Tick)
    Some(resolved)
  }

  private def findHeaderInScope(scope: Locator, groupName: String): Option[Locator] = {
    val headers = scope.locator(".ant-collapse-header")
    val count = headers.count()
    val target = normalize(groupName)

    def readHeader(i: Int): (Locator, Boolean, String) = {
      val header = headers.nth(i)
      val visible = try header.isVisible() catch { case _: Exception => false }
      val label = try Option(header.innerText()).getOrElse("").replaceAll("\\s+", " ").trim
      catch { case _: Exception => "" }
      (header, visible, normalize(label))
    }

    // Pass 1: exact match — avoids matching a parent header whose name is a
    // substring of the target (e.g. "Sklearn" when looking for "Sklearn Training").
    var i = 0
    while (i < count) {
      val (header, visible, norm) = readHeader(i)
      if (visible && norm == target) return Some(header)
      i += 1
    }

    // Pass 2: fuzzy (substring) match as fallback.
    i = 0
    while (i < count) {
      val (header, visible, norm) = readHeader(i)
      if (visible && (norm.contains(target) || target.contains(norm))) return Some(header)
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
  private var _versionOnly: Boolean = false

  def datasetName(name: String): this.type = { _datasetName = name; this }
  def datasetVersion(version: String): this.type = { _versionName = version; this }
  def file(name: String): this.type = { _fileName = Some(name); this }
  def versionOnly(): this.type = { _versionOnly = true; this }

  def fromConfig(datasetKey: String): this.type = {
    val ds = TestDataConfig.datasets(datasetKey)
    _datasetName = ds.name
    _versionName = ds.version
    this
  }

  override def execute(): Unit = {
    require(_datasetName != null && _datasetName.nonEmpty, "datasetName is required")
    require(_versionName != null && _versionName.nonEmpty, "datasetVersion is required")
    addStep(new DatasetSelectionStep(_datasetName, _versionName, _fileName, _versionOnly))
    super.execute()
  }

  private class DatasetSelectionStep(
    datasetName: String,
    versionName: String,
    fileName: Option[String],
    versionOnly: Boolean
  ) extends ControllerStep {
    override def name = if (versionOnly) s"Select Dataset $datasetName/$versionName"
                        else s"Select File from $datasetName/$versionName"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page

      def firstVisibleOrFirst(locator: Locator): Locator = {
        FormHelpers.firstVisible(locator).getOrElse(locator.first())
      }

      val selectBtn = firstVisibleOrFirst(
        if (versionOnly)
          page.locator("#property-editor button:has-text('Select Dataset')")
        else
          page.locator(
            "[data-testid='dataset-file-selection-open'], " +
            "[data-testid='file-selection-open'], " +
            "#property-editor button:has-text('Select File')"
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
          "[data-testid='dataset-file-selection-dataset'] .ant-select-selector, " +
          "[data-testid='file-selection-dataset'] .ant-select-selector, " +
          "nz-select:nth-of-type(1) .ant-select-selector"
        )
      )
      Utils.waitVisible(datasetBox)
      Utils.clickWithCursor(page, datasetBox)
      page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
      Utils.chooseDropdownOptionByText(page, datasetName)

      val versionBoxCandidates = modal.locator(
        "[data-testid='dataset-file-selection-version'] .ant-select-selector, " +
        "[data-testid='file-selection-version'] .ant-select-selector, " +
        "nz-select:nth-of-type(2) .ant-select-selector"
      )
      if (versionBoxCandidates.count() > 0) {
        val versionBox = firstVisibleOrFirst(versionBoxCandidates)
        Utils.waitVisible(versionBox)
        Utils.clickWithCursor(page, versionBox)
        page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").last()
          .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE))
        Utils.chooseDropdownOptionByText(page, versionName)
      }

      if (!versionOnly) {
        val fileTree = firstVisibleOrFirst(
          modal.locator(
            "[data-testid='dataset-file-selection-filetree'], [data-testid='file-selection-filetree'], tree-root"
          )
        )
        Utils.waitVisible(fileTree)

        // Click the .node-content-wrapper (the angular-tree-component clickable cell), not
        // an inner <span> — clicking deep children may not propagate to the tree's
        // (selectedTreeNode) handler, which means the modal's `selectedPath` stays
        // unset and the "Select" confirm button stays disabled.
        val fileNode = fileName match {
          case Some(fn) =>
            val wrapper = fileTree
              .locator(".node-content-wrapper")
              .filter(new Locator.FilterOptions().setHasText(fn))
              .first()
            if (wrapper.count() > 0) wrapper
            else fileTree.getByText(fn).first()
          case None =>
            firstVisibleOrFirst(fileTree.locator(".node-content-wrapper"))
        }
        if (fileNode.count() > 0) {
          Utils.waitVisible(fileNode)
          Utils.clickWithCursor(page, fileNode)
          page.waitForTimeout(Delays.Settle)
        }
      }

      val confirmBtn = FormHelpers.firstVisible(
        modal.locator("button.ant-btn-primary:has-text('OK'), button.ant-btn-primary:has-text('Confirm'), button.ant-btn-primary:has-text('Select')")
      ).orElse(
        FormHelpers.firstVisible(modal.locator("button.ant-btn-primary"))
      ).orNull
      if (confirmBtn != null && confirmBtn.count() > 0) {
        Utils.clickWithCursor(page, confirmBtn)
        page.waitForTimeout(Delays.Long)
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
  private val DEBUG = sys.env.getOrElse("TEXERA_DOCS_DEBUG", "false").toBoolean
  private def debug(msg: => String): Unit = if (DEBUG) println(msg)
  private def lastVisible(locator: Locator): Option[Locator] = {
    val count = locator.count()
    var i = count - 1
    while (i >= 0) {
      val nth = locator.nth(i)
      val visible = try nth.isVisible() catch { case _: Exception => false }
      if (visible) return Some(nth)
      i -= 1
    }
    None
  }

  // Collect all operator schema titles keyed by normalized field key.
  // This replaces the need for hardcoded aliases in most cases.
  private lazy val metadataTitlesByKey: Map[String, Seq[String]] = {
    val grouped = scala.collection.mutable.Map.empty[String, scala.collection.mutable.LinkedHashSet[String]]

    def addTitle(rawKey: String, title: String): Unit = {
      val key = normalize(rawKey)
      val clean = Option(title).getOrElse("").trim
      if (key.nonEmpty && clean.nonEmpty) {
        val acc = grouped.getOrElseUpdate(key, scala.collection.mutable.LinkedHashSet.empty[String])
        acc += clean
      }
    }

    def walkSchema(node: JsonNode): Unit = {
      if (node == null || node.isNull || node.isMissingNode) return

      val properties = node.path("properties")
      if (properties.isObject) {
        properties.fields().asScala.foreach { entry =>
          val fieldKey = entry.getKey
          val fieldSchema = entry.getValue
          val titleNode = fieldSchema.path("title")
          if (!titleNode.isMissingNode && !titleNode.isNull) {
            addTitle(fieldKey, titleNode.asText(""))
          }
          walkSchema(fieldSchema)
        }
      }

      val items = node.path("items")
      if (!items.isMissingNode && !items.isNull) {
        if (items.isArray) items.elements().asScala.foreach(walkSchema)
        else walkSchema(items)
      }

      Seq("oneOf", "anyOf", "allOf").foreach { key =>
        val variants = node.path(key)
        if (variants.isArray) variants.elements().asScala.foreach(walkSchema)
      }

      val definitions = node.path("definitions")
      if (definitions.isObject) {
        definitions.fields().asScala.foreach(e => walkSchema(e.getValue))
      }

      val defs = node.path("$defs")
      if (defs.isObject) {
        defs.fields().asScala.foreach(e => walkSchema(e.getValue))
      }
    }

    OperatorMetadataGenerator.allOperatorMetadata.operators.foreach { metadata =>
      walkSchema(metadata.jsonSchema)
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
    val fallbackAliases = normalized match {
      // Repeat-row nested configs sometimes expose simplified titles in UI.
      // Keep these as minimal, targeted fallbacks when schema titles are absent.
      case "originalattribute" => Seq("Attribute")
      case "attributename" => Seq("Attribute Name", "Attribute")
      case "resulttype" => Seq("Cast type", "Result Type")
      case _ => Seq.empty
    }

    // Per-operator override from _controllerHints.formFieldLabels takes priority
    // when present — lets the auto-fix agent patch a specific field's UI label
    // without changing the canonical metadata lookup.
    val hintOverride: Seq[String] = context.currentOperatorType
      .flatMap(t => OperatorFieldValues.formFieldLabelOverride(t, fieldKey))
      .toSeq

    (hintOverride ++ metadataTitles ++ fallbackAliases :+ pretty)
      .map(_.trim).filter(_.nonEmpty).distinct
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

  private def rowEditorScopes(section: Locator): Seq[Locator] = {
    val selectors = Seq(
      "formly-field:has(formly-group)",
      "formly-field[class*='repeat'] formly-field",
      ".ant-form-item",
      "formly-field"
    )
    selectors.foreach { s =>
      val nodes = section.locator(s)
      val count = nodes.count()
      if (count > 0) {
        val acc = scala.collection.mutable.ArrayBuffer.empty[Locator]
        var i = 0
        while (i < count) {
          val n = nodes.nth(i)
          val visible = try n.isVisible() catch { case _: Exception => false }
          if (visible) acc += n
          i += 1
        }
        if (acc.nonEmpty) return acc.toSeq
      }
    }
    Seq.empty
  }

  private def resolveFieldInLatestRow(section: Locator, subKey: String): Locator = {
    val rows = rowEditorScopes(section)
    if (rows.nonEmpty) {
      var i = rows.size - 1
      while (i >= 0) {
        val candidate = resolveFieldInScope(rows(i), subKey)
        if (candidate.count() > 0) return candidate
        i -= 1
      }
    }
    resolveFieldInScope(section, subKey)
  }

  private def latestRowScope(section: Locator): Locator = {
    val rows = rowEditorScopes(section)
    if (rows.nonEmpty) rows.last else section
  }

  private def fieldLooksFilled(field: Locator): Boolean = {
    val scope = field.locator("xpath=ancestor-or-self::formly-field[1]").first()
    val container =
      if (scope.count() > 0) scope
      else {
        val item = field.locator("xpath=ancestor-or-self::*[contains(@class,'ant-form-item')][1]").first()
        if (item.count() > 0) item else field
      }

    val selectItems = container.locator(".ant-select-selection-item")
    val selectedCount = selectItems.count()
    var i = 0
    while (i < selectedCount) {
      val txt = try Option(selectItems.nth(i).innerText()).getOrElse("").trim
      catch { case _: Exception => "" }
      if (txt.nonEmpty) return true
      i += 1
    }

    val hasSelect = container.locator("nz-select, .ant-select, [role='combobox']").count() > 0
    if (hasSelect) {
      val hasPlaceholder = container.locator(".ant-select-selection-placeholder").count() > 0
      if (!hasPlaceholder) return true
    }

    val textInputs = container.locator("textarea, input:not([type='checkbox']):not([type='radio'])")
    val inputCount = textInputs.count()
    var j = 0
    while (j < inputCount) {
      val v = try Option(textInputs.nth(j).inputValue()).getOrElse("").trim
      catch { case _: Exception => "" }
      if (v.nonEmpty) return true
      j += 1
    }
    false
  }

  private def findEmptyRowForSubKey(section: Locator, subKey: String): Option[Locator] = {
    val rows = rowEditorScopes(section)
    var i = 0
    while (i < rows.size) {
      val row = rows(i)
      val field = resolveFieldInScope(row, subKey)
      val visible = field.count() > 0 && (try field.isVisible() catch { case _: Exception => false })
      if (visible && !fieldLooksFilled(field)) return Some(row)
      i += 1
    }
    None
  }

  private def allMatchingFieldsInScope(scope: Locator, fieldKey: String): Seq[Locator] = {
    val labels = labelCandidates(fieldKey)
    val fields = scope.locator("formly-field")
    val total = fields.count()
    val acc = scala.collection.mutable.ArrayBuffer.empty[Locator]
    var i = 0
    while (i < total) {
      val candidate = fields.nth(i)
      val visible = try candidate.isVisible() catch { case _: Exception => false }
      if (visible && hasEditableControl(candidate)) {
        var matched = false
        var j = 0
        while (j < labels.size && !matched) {
          if (hasExactLabel(candidate, labels(j))) matched = true
          j += 1
        }
        if (matched) acc += candidate
      }
      i += 1
    }
    acc.toSeq
  }

  private def findEmptyFieldInSection(section: Locator, subKey: String): Option[Locator] = {
    val matches = allMatchingFieldsInScope(section, subKey)
    matches.find(f => !fieldLooksFilled(f))
  }

  private def resolveArraySection(root: Locator, fieldKey: String): Locator = {
    val labels = labelCandidates(fieldKey)
    var i = 0
    while (i < labels.size) {
      val label = labels(i)
      val byFormly = root.locator(s"formly-field:has(label:has-text('$label'))").last()
      if (byFormly.count() > 0) return byFormly
      i += 1
    }
    root
  }

  private def resolveArrayAddButton(section: Locator, root: Locator, fieldKey: String): Locator = {
    val labels = labelCandidates(fieldKey)
    val selectors = Seq(
      "button:has(i.anticon-plus)",
      "button.ant-btn-circle",
      "button.ant-btn-primary",
      ".ant-btn:has-text('Add')",
      "button[aria-label='add']"
    )

    // Highest priority: locate button around the specific array section title (e.g. "Add attribute").
    labels.foreach { label =>
      val anchor = root.getByText(label, new Locator.GetByTextOptions().setExact(false)).first()
      if (anchor.count() > 0) {
        val row = anchor.locator("xpath=ancestor::*[self::formly-field or self::div][1]").first()
        if (row.count() > 0) {
          selectors.foreach { s =>
            lastVisible(row.locator(s)).foreach(return _)
          }
          lastVisible(row.locator("button")).foreach(return _)
        }
      }
    }

    // Fallback: any visible add-like button in section then root.
    selectors.foreach { s =>
      lastVisible(section.locator(s)).foreach(return _)
    }
    selectors.foreach { s =>
      lastVisible(root.locator(s)).foreach(return _)
    }
    section.locator("__not_found__").first()
  }

  private def fillArrayItemsNow(
    page: Page,
    fieldKey: String,
    items: Seq[Map[String, String]]
  ): Unit = {
    if (items.isEmpty) return
    val root = propertyEditorRoot(page)
    val section = resolveArraySection(root, fieldKey)

    items.zipWithIndex.foreach { case (itemValues, itemIndex) =>
      val firstSubKey = itemValues.keys.headOption.getOrElse("")

      // Fast path for array rows with a single sub-field (e.g. FigureFactoryTable "add attribute").
      if (firstSubKey.nonEmpty && itemValues.size == 1) {
        def collectFields(): Seq[Locator] = {
          val inSection = allMatchingFieldsInScope(section, firstSubKey)
          if (inSection.nonEmpty) inSection else allMatchingFieldsInScope(root, firstSubKey)
        }
        var fields = collectFields()
        var targetField = section.locator("__not_found__").first()

        // Many repeat-array UIs render one default row.
        // For the first configured item, fill that row first instead of clicking '+'.
        if (itemIndex == 0) {
          val existing = {
            val inSection = resolveFieldInScope(section, firstSubKey)
            if (inSection.count() > 0) inSection else resolveFieldInLatestRow(section, firstSubKey)
          }
          if (existing.count() > 0) targetField = existing
          else if (fields.nonEmpty) targetField = fields.head
        }

        var addClicks = 0
        while (targetField.count() == 0 && fields.size <= itemIndex && addClicks < 6) {
          val addBtn = resolveArrayAddButton(section, root, fieldKey)
          if (addBtn.count() == 0) {
            throw new RuntimeException(s"Add button not found for array field: $fieldKey")
          }
          try addBtn.scrollIntoViewIfNeeded() catch { case _: Exception => }
          val box = try addBtn.boundingBox() catch { case _: Exception => null }
          if (box != null) {
            debug(f"  click '+' for '$fieldKey' at x=${box.x}%.1f y=${box.y}%.1f")
          }
          Utils.clickWithCursor(page, addBtn)
          debug(s"  clicked '+' for array field: $fieldKey")
          page.waitForTimeout(Delays.Settle)
          addClicks += 1
          fields = collectFields()
          if (fields.size > itemIndex) {
            targetField = fields(itemIndex)
          } else if (itemIndex == 0) {
            val existing = resolveFieldInLatestRow(section, firstSubKey)
            if (existing.count() > 0) targetField = existing
          }
        }

        if (targetField.count() == 0 && fields.size > itemIndex) {
          targetField = fields(itemIndex)
        }

        if (targetField.count() == 0) {
          throw new RuntimeException(s"Array row did not appear for '$fieldKey' at index $itemIndex")
        }

        val value = itemValues(firstSubKey)
        var filled =
          FormHelpers.tryFillSelect(page, targetField, Some(value), allowFallbackToFirst = false) ||
            FormHelpers.tryFillText(page, targetField, value)
        if (!filled) {
          filled = LLMFormRecovery.tryRecoverArraySubField(
            page = page,
            arrayFieldKey = fieldKey,
            arrayLabelHints = labelCandidates(fieldKey),
            subFieldKey = firstSubKey,
            subFieldLabelHints = labelCandidates(firstSubKey),
            expectedValue = value
          )
        }
        if (!filled) {
          throw new RuntimeException(
            s"Failed to fill sub-field '$firstSubKey' with value '$value' in array field '$fieldKey'"
          )
        }
        page.waitForTimeout(Delays.Tick)
      } else {
      var targetRow: Locator = section

      if (firstSubKey.nonEmpty) {
        def collectFirstKeyFields(): Seq[Locator] = {
          val inSection = allMatchingFieldsInScope(section, firstSubKey)
          if (inSection.nonEmpty) inSection else allMatchingFieldsInScope(root, firstSubKey)
        }
        var firstKeyFields = collectFirstKeyFields()

        // Repeat-array forms frequently start with one empty row.
        // Prefer that default row for the first configured item.
        if (itemIndex == 0) {
          val existingRow = findEmptyRowForSubKey(section, firstSubKey)
          existingRow.foreach { row =>
            targetRow = row
          }
        }

        var addClicks = 0
        while (targetRow == section && firstKeyFields.size <= itemIndex && addClicks < 6) {
          val addBtn = resolveArrayAddButton(section, root, fieldKey)
          if (addBtn.count() == 0) {
            throw new RuntimeException(s"Add button not found for array field: $fieldKey")
          }
          try addBtn.scrollIntoViewIfNeeded() catch { case _: Exception => }
          val box = try addBtn.boundingBox() catch { case _: Exception => null }
          if (box != null) {
            debug(f"  click '+' for '$fieldKey' at x=${box.x}%.1f y=${box.y}%.1f")
          }
          Utils.clickWithCursor(page, addBtn)
          debug(s"  clicked '+' for array field: $fieldKey")
          page.waitForTimeout(Delays.Settle)
          addClicks += 1
          firstKeyFields = collectFirstKeyFields()
        }

        if (targetRow == section) {
          if (firstKeyFields.size <= itemIndex) {
            throw new RuntimeException(s"Array row did not appear for '$fieldKey' at index $itemIndex")
          }
          val keyField = firstKeyFields(itemIndex)
          val rowFormly = keyField.locator("xpath=ancestor-or-self::formly-field[1]").first()
          val rowItem = keyField.locator("xpath=ancestor-or-self::*[contains(@class,'ant-form-item')][1]").first()
          targetRow =
            if (rowFormly.count() > 0) rowFormly
            else if (rowItem.count() > 0) rowItem
            else keyField
        } else {
          debug(s"  use existing default row for '$fieldKey' index=$itemIndex")
        }
      } else {
        println(s"  [WARN] array item for '$fieldKey' has no subKey")
        targetRow = latestRowScope(section)
      }

      itemValues.foreach { case (subKey, value) =>
        val fieldInRow = resolveFieldInScope(targetRow, subKey)
        val field = if (fieldInRow.count() > 0) fieldInRow else resolveFieldInLatestRow(section, subKey)

        // JSON booleans arrive as "true"/"false" strings; try boolean controls
        // before tryFillSelect/tryFillText fallbacks to avoid spurious failures
        // on switch / checkbox sub-fields.
        val looksBoolean = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")
        var filled = false
        if (field.count() > 0) {
          if (looksBoolean) {
            filled = FormHelpers.trySetBoolean(page, field, target = value.equalsIgnoreCase("true"))
          }
          if (!filled) {
            filled =
              FormHelpers.tryFillSelect(page, field, Some(value), allowFallbackToFirst = false) ||
                FormHelpers.tryFillText(page, field, value)
          }
        }

        // Fallback for repeat rows with a single select control (e.g., "Add attribute" lists).
        if (!filled) {
          val rowSelect = targetRow.locator("nz-select, .ant-select, [role='combobox']").first()
          if (rowSelect.count() > 0) {
            filled = FormHelpers.tryFillSelect(page, rowSelect, Some(value), allowFallbackToFirst = false)
          }
        }

        if (!filled) {
          filled = LLMFormRecovery.tryRecoverArraySubField(
            page = page,
            arrayFieldKey = fieldKey,
            arrayLabelHints = labelCandidates(fieldKey),
            subFieldKey = subKey,
            subFieldLabelHints = labelCandidates(subKey),
            expectedValue = value
          )
        }

        if (!filled) {
          def sampleLabels(scope: Locator, limit: Int = 12): Seq[String] = {
            val labels = scope.locator("label")
            val total = labels.count()
            val buf = scala.collection.mutable.ArrayBuffer.empty[String]
            var i = 0
            while (i < total && buf.size < limit) {
              val txt = try Option(labels.nth(i).innerText()).getOrElse("").trim catch { case _: Exception => "" }
              if (txt.nonEmpty) buf += txt
              i += 1
            }
            buf.toSeq
          }
          val rowLabelSample = sampleLabels(targetRow).mkString(" | ")
          val sectionLabelSample = sampleLabels(section).mkString(" | ")
          val rowSelectCount = targetRow.locator("nz-select, .ant-select, [role='combobox']").count()
          val sectionSelectCount = section.locator("nz-select, .ant-select, [role='combobox']").count()
          debug(s"  fill fail subKey='$subKey' value='$value' rowSelectCount=$rowSelectCount sectionSelectCount=$sectionSelectCount")
          debug(s"  row labels: $rowLabelSample")
          debug(s"  section labels: $sectionLabelSample")
          throw new RuntimeException(s"Failed to fill sub-field '$subKey' with value '$value' in array field '$fieldKey'")
        }
        page.waitForTimeout(Delays.Tick)
      }
      }
    }

    // After filling array items, close any stale dropdown and click a neutral area
    // to prevent the next field fill from interacting with a lingering overlay.
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() > 0) {
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(Delays.Settle)
    }
    // Click a neutral area to deselect
    val neutral = page.locator("#property-editor .property-editor-title, #property-editor header").first()
    if (neutral.count() > 0) {
      try Utils.clickWithCursor(page, neutral) catch { case _: Exception => }
      page.waitForTimeout(Delays.Long)
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
      // Booleans first: a toggle (e.g. countVectorizer) often controls visibility of a sibling
      // field (e.g. text). Filling the toggle later means the sibling locator misses on first pass.
      val ordered = values.toSeq.sortBy { case (_, n) =>
        if (n != null && !n.isNull && n.isBoolean) 0 else 1
      }
      ordered.foreach { case (fieldKey, node) =>
        if (node == null || node.isNull) {
          // skip null
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
            debug(s"  key='$fieldKey' array-object rows=${rows.size}")
            fillArrayItemsNow(page, fieldKey, rows)
          } else {
            val field = resolveField(page, fieldKey)
            val actualLabel = field.locator("label").first()
            val labelText = if (actualLabel.count() > 0) actualLabel.innerText().trim() else "NO LABEL"
            debug(s"  key='$fieldKey' → resolved label='$labelText' found=${field.count() > 0}")
            if (field.count() == 0) {
              println(s"  Field not found: $fieldKey")
            } else {
            val values = elements
              .filter(n => n.isTextual || n.isNumber)
              .map(_.asText())
            if (!FormHelpers.tryFillSelectMany(page, field, values)) {
              println(s"  No supported array-select input for field: $fieldKey")
            }
            }
          }
        } else {
          val field = resolveField(page, fieldKey)
          val actualLabel = field.locator("label").first()
          val labelText = if (actualLabel.count() > 0) actualLabel.innerText().trim() else "NO LABEL"
          debug(s"  [DEBUG] key='$fieldKey' → resolved label='$labelText' found=${field.count() > 0}")

          if (field.count() == 0) {
            println(s"  Field not found: $fieldKey")
          } else if (node.isBoolean) {
            val target = node.asBoolean()
            if (!FormHelpers.trySetBoolean(page, field, target = target)) {
              println(s"  No supported boolean input for field: $fieldKey")
            }
          } else if (node.isTextual || node.isNumber) {
            val value = node.asText()
            if (
              !FormHelpers.tryFillSelect(page, field, Some(value)) &&
              !FormHelpers.tryFillText(page, field, value) &&
              !LLMFormRecovery.tryRecoverSingleField(page, fieldKey, labelCandidates(fieldKey), value)
            ) {
              println(s"  No supported input for field: $fieldKey")
            }
          } else {
            println(s"  Skip unsupported JSON type for field: $fieldKey")
          }
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

  private def runButton(page: Page): Locator = {
    // Per-operator hint, if any, is OR'd into the locator selector so we don't
    // have to time the `count()` check against async UI render — downstream
    // waitVisible handles the wait. The default `#run-button` is always part
    // of the selector, so a bad hint cannot remove the fallback.
    val selector = context.currentOperatorType
      .flatMap(OperatorFieldValues.runButtonSelector)
      .map(hint => s"$hint, #run-button")
      .getOrElse("#run-button")
    page.locator(selector).first()
  }

  private def buttonText(button: Locator): String = {
    try Option(button.innerText()).getOrElse("").replaceAll("\\s+", " ").trim
    catch { case _: Exception => "" }
  }

  private def isDisabled(button: Locator): Boolean = {
    val disabledAttr = try Option(button.getAttribute("disabled")).getOrElse("") catch { case _: Exception => "" }
    val ariaDisabled = try Option(button.getAttribute("aria-disabled")).getOrElse("") catch { case _: Exception => "" }
    val cls = try Option(button.getAttribute("class")).getOrElse("") catch { case _: Exception => "" }
    disabledAttr.nonEmpty ||
      ariaDisabled.equalsIgnoreCase("true") ||
      cls.contains("ant-btn-disabled") ||
      cls.contains("ant-menu-item-disabled")
  }

  // Property panel can be dragged and sometimes overlaps top controls (Run / CU dropdown).
  // Before execution, normalize UI by closing transient overlays and docking the property panel.
  private def normalizeTopToolbarArea(page: Page): Unit = {
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() > 0) {
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(Delays.Tick)
    }

    val propertyPanel = page.getByTestId("property-panel").first()
    val panelVisible =
      propertyPanel.count() > 0 && (try propertyPanel.isVisible() catch { case _: Exception => false })
    if (panelVisible) {
      val closeBtn = propertyPanel
        .locator("#property-buttons li[nz-menu-item]:has(.anticon-minus)")
        .first()
        .or(propertyPanel.locator("#property-buttons li[nz-menu-item]").first())
        .or(propertyPanel.locator(".anticon-minus").first())
      if (closeBtn.count() > 0) {
        try Utils.clickWithCursor(page, closeBtn) catch { case _: Exception => }
        page.waitForTimeout(Delays.Settle)
      }
    }
  }

  // Before final result capture, close side panels so result content has more room.
  private def normalizeResultCaptureArea(page: Page): Unit = {
    if (page.locator(".cdk-overlay-container .ant-select-dropdown:not(.ant-select-dropdown-hidden)").count() > 0) {
      try page.keyboard().press("Escape") catch { case _: Exception => }
      page.waitForTimeout(Delays.Tick)
    }

    val propertyPanel = page.getByTestId("property-panel").first()
    val propertyVisible =
      propertyPanel.count() > 0 && (try propertyPanel.isVisible() catch { case _: Exception => false })
    if (propertyVisible) {
      val closeBtn = propertyPanel
        .locator("#property-buttons li[nz-menu-item]:has(.anticon-minus)")
        .first()
        .or(propertyPanel.locator("#property-buttons li[nz-menu-item]").first())
        .or(propertyPanel.locator(".anticon-minus").first())
      if (closeBtn.count() > 0) {
        try Utils.clickWithCursor(page, closeBtn) catch { case _: Exception => }
        page.waitForTimeout(Delays.Tick)
      }
    }

    val leftPanel = page.locator("#left-container").first()
    val leftVisible =
      leftPanel.count() > 0 && (try leftPanel.isVisible() catch { case _: Exception => false })
    if (leftVisible) {
      val closeBtn = leftPanel
        .locator("#return-button li[nz-menu-item]:has(.anticon-minus)")
        .first()
        .or(leftPanel.locator("#dock li[nz-menu-item]:has(.anticon-minus)").first())
        .or(leftPanel.locator(".anticon-minus").first())
      if (closeBtn.count() > 0) {
        try Utils.clickWithCursor(page, closeBtn) catch { case _: Exception => }
        page.waitForTimeout(Delays.Tick)
      }
    }
  }

  private def clickToolbarButton(page: Page, button: Locator): Unit = {
    try {
      Utils.clickWithCursor(page, button)
    } catch {
      case _: Exception =>
        // Fallback when a floating panel intercepts pointer checks in Playwright.
        button.click(new Locator.ClickOptions().setForce(true))
    }
  }

  // Keep result panel focused for capture. Do not resize/reposition.
  private def expandResultPanel(page: Page): Unit = {
    val panel = page.locator("#result-container").first()
    val panelVisible = panel.count() > 0 && (try panel.isVisible() catch { case _: Exception => false })
    if (!panelVisible) return

    // A single title click is enough for stable capture in current UI.
    val titleHandle = panel.locator("#title").first()
    if (titleHandle.count() > 0) {
      try Utils.clickWithCursor(page, titleHandle) catch { case _: Exception => }
      page.waitForTimeout(Delays.Tick)
    }

    // Slightly lift panel by dragging title upward once (small, controlled move).
    def dragTitleUp(dy: Double): Unit = {
      if (dy <= 2.0 || titleHandle.count() == 0) return
      val b = titleHandle.boundingBox()
      if (b == null) return
      val x = b.x + math.min(120.0, b.width * 0.5)
      val y = b.y + b.height / 2.0
      page.mouse().move(x, y, new Mouse.MoveOptions().setSteps(8))
      page.mouse().down()
      page.mouse().move(x, y - dy, new Mouse.MoveOptions().setSteps(14))
      page.mouse().up()
      page.waitForTimeout(Delays.Tick)
    }

    val panelBox = panel.boundingBox()
    val viewport = Option(page.viewportSize())
    if (panelBox != null && viewport.nonEmpty) {
      val v = viewport.get
      val overflowBottom = math.max(0.0, panelBox.y + panelBox.height - (v.height - 12.0))
      val gentleUp = if (panelBox.y > 110.0) 70.0 else 0.0
      val maxAllowedByTop = math.max(0.0, panelBox.y - 56.0)
      val dy = math.min(maxAllowedByTop, math.min(110.0, math.max(gentleUp, overflowBottom + 12.0)))
      dragTitleUp(dy)
    }
  }

  private def createOrSelectComputingUnit(page: Page, timeoutMs: Int): Boolean = {
    val dropdownBtn = page.locator(".computing-units-dropdown-button").first()
    if (dropdownBtn.count() == 0) return false
    Utils.waitVisible(dropdownBtn)
    Utils.clickWithCursor(page, dropdownBtn)

    val dropdown = page.locator(".computing-units-dropdown").first()
    dropdown.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Medium))

    val existing = dropdown.locator("#computing-unit-option:not(.ant-dropdown-menu-item-disabled)").first()
    if (existing.count() > 0) {
      Utils.clickWithCursor(page, existing)
      page.waitForTimeout(Delays.Network)
      return true
    }

    val createEntry = dropdown.locator(".create-computing-unit").first()
    if (createEntry.count() == 0) return false
    Utils.clickWithCursor(page, createEntry)

    val modal = page.locator(".ant-modal-wrap .ant-modal-content").last()
    modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(Timeouts.Medium))

    val typeSelector = modal.locator(".type-selection .ant-select-selector").first()
    if (typeSelector.count() > 0) {
      Utils.clickWithCursor(page, typeSelector)
      Utils.chooseDropdownOptionByText(page, "Local")
      page.waitForTimeout(Delays.Settle)
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
      page.waitForTimeout(Delays.Settle)
    }
    false
  }

  private def ensureComputingUnitReadyInternal(page: Page, timeoutMs: Int): Unit = {
    val btn = runButton(page)
    Utils.waitVisible(btn)

    val startText = buttonText(btn)
    val startDisabled = isDisabled(btn)
    if (!startDisabled && !startText.equalsIgnoreCase(RunButtonStates.Connect)) return

    val prepared = createOrSelectComputingUnit(page, timeoutMs)
    if (!prepared) return

    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      val text = buttonText(btn)
      val disabled = isDisabled(btn)
      if (!disabled && !text.equalsIgnoreCase(RunButtonStates.Connect)) return
      page.waitForTimeout(Delays.Network)
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

  def runWorkflowAndWait(timeoutMs: Int = 240000): this.type = addStep(new ControllerStep {
    override def name = "Run Workflow And Wait"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()
      normalizeTopToolbarArea(page)

      val btn = runButton(page)
      Utils.waitVisible(btn)

      def waitUntilEnabled(maxWaitMs: Int): Boolean = {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
          if (!isDisabled(btn)) return true
          page.waitForTimeout(Delays.Settle)
        }
        !isDisabled(btn)
      }

      var initial = buttonText(btn)
      if (isDisabled(btn)) {
        if (initial.equalsIgnoreCase(RunButtonStates.Connect)) {
          ensureComputingUnitReadyInternal(page, timeoutMs = 90000)
          initial = buttonText(btn)
          if (!waitUntilEnabled(6000)) {
            println(s"[Execution] Skip run: run button stays disabled in '$initial' state.")
            return
          }
          initial = buttonText(btn)
        } else if (initial.equalsIgnoreCase(RunButtonStates.Connecting)) {
          if (!waitUntilEnabled(90000)) {
            println(s"[Execution] Skip run: compute unit still connecting after 90s.")
            return
          }
          initial = buttonText(btn)
        } else if (!waitUntilEnabled(if (initial.equalsIgnoreCase(RunButtonStates.Invalid)) 30000 else 8000)) {
          throw new RuntimeException(s"Run button is disabled. Current text: '$initial'")
        }
      }

      if (initial.equalsIgnoreCase(RunButtonStates.Connect)) {
        if (!isDisabled(btn)) {
          clickToolbarButton(page, btn)
          page.waitForTimeout(Delays.Network)
        }
      }

      val beforeRun = buttonText(btn)
      if (beforeRun.equalsIgnoreCase(RunButtonStates.Run)) {
        if (isDisabled(btn)) {
          println("[Execution] Skip run: 'Run' is visible but disabled.")
          return
        }
        clickToolbarButton(page, btn)
      } else if (beforeRun.equalsIgnoreCase(RunButtonStates.Connect)) {
        println("[Execution] Skip run: still in 'Connect' state after connect attempt.")
        return
      }

      val start = System.currentTimeMillis()
      val initialLower = initial.toLowerCase
      var seenTransition = false
      while (System.currentTimeMillis() - start < timeoutMs) {
        val text = buttonText(btn)
        val t = text.toLowerCase
        if (t.nonEmpty && t != initialLower) {
          seenTransition = true
        }

        if (seenTransition && t.equalsIgnoreCase(RunButtonStates.Run)) {
          page.waitForTimeout(Delays.Long)
          return
        }

        if (!seenTransition && t.equalsIgnoreCase(RunButtonStates.Run) && System.currentTimeMillis() - start > 5000) {
          return
        }

        page.waitForTimeout(Delays.Long)
      }

      val finalText = buttonText(btn)
      // Used to silently return when the button was still 'Pause' (workflow still
      // running at timeout). That masked operators whose workflows never actually
      // completed — the demo video saved was for an in-progress, empty result.
      // Now we surface this as a hard failure so AutoFix's LLM gets a chance to
      // propose a config patch (different column, smaller degree, countVectorizer
      // toggle, etc.) instead of being silently bypassed.
      throw new RuntimeException(
        s"Workflow execution did not finish within ${timeoutMs}ms. Run button text='$finalText'."
      )
    }
  })

  // Click the eye / "view result" icon on the currently-selected operator BEFORE
  // running the workflow. The toolbar/menu uses `title="view result"` for the OFF→ON
  // toggle; once enabled, the title changes (e.g., "remove view result") and this
  // selector no longer matches — so calling enableViewResult twice is idempotent
  // (the second call is a silent no-op).
  def enableViewResult(): this.type = addStep(new ControllerStep {
    override def name = "Enable View Result"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val enableBtn = page.getByTitle("view result").first()
      if (enableBtn.count() > 0 && !isDisabled(enableBtn)) {
        Utils.clickWithCursor(page, enableBtn)
        page.waitForTimeout(Delays.Settle)
      }
    }
  })

  def openResultPanel(timeoutMs: Int = 25000): this.type = addStep(new ControllerStep {
    override def name = "Open Result Panel"
    override def run(ctx: ControllerContext): Unit = {
      val page = ctx.page
      ctx.ensureFakeCursor()

      val title = page.locator("#result-container #title").first()
      val content = page.locator("#result-container #content").first()
      val staticError = page.getByText("Static Error", new Page.GetByTextOptions().setExact(false)).first()
      val compilationError = page.getByText("COMPILATION_ERROR", new Page.GetByTextOptions().setExact(false)).first()

      def titleVisible: Boolean = {
        if (title.count() == 0) false
        else {
          try title.isVisible()
          catch { case _: Exception => false }
        }
      }

      def contentVisible: Boolean = {
        if (content.count() == 0) false
        else {
          try content.isVisible()
          catch { case _: Exception => false }
        }
      }

      def panelVisible: Boolean = titleVisible || contentVisible

      def hasCompilationErrorVisible: Boolean = {
        (staticError.count() > 0 && (try staticError.isVisible() catch { case _: Exception => false })) ||
        (compilationError.count() > 0 && (try compilationError.isVisible() catch { case _: Exception => false }))
      }

      if (panelVisible) {
        normalizeResultCaptureArea(page)
        expandResultPanel(page)
        assertPanelHasContent(page)
        return
      }

      // Note: the "view result" eye icon should already have been clicked BEFORE
      // runWorkflowAndWait via ExecutionControllerBuilder.enableViewResult(). We do
      // NOT click it here — by the time the panel needs opening, the button title
      // has changed to "remove view result" and clicking would TOGGLE OFF the
      // result linkage, hiding the panel we are trying to open.

      var retries = 0
      while (retries < Retries.Medium && !panelVisible) {
        page.waitForTimeout(Delays.Settle)
        retries += 1
      }

      if (!panelVisible) {
        val openResultBtn = page.locator("#result-buttons li[nz-menu-item]:has(.anticon-border)").first()
        val fallbackOpenBtn = page.locator("#result-buttons li[nz-menu-item][nz-tooltip*='Open Result Panel']").first()
        val targetBtn =
          if (openResultBtn.count() > 0) openResultBtn
          else fallbackOpenBtn
        if (targetBtn.count() > 0 && !isDisabled(targetBtn)) {
          Utils.clickWithCursor(page, targetBtn)
          page.waitForTimeout(Delays.Settle)
        }
      }

      if (!panelVisible) {
        val genericOpenBtn = page.locator("#result-buttons li[nz-menu-item]").first()
        // Best-effort fallback for older UI variants.
        // Keep this as last resort because generic selector can hit close button in some states.
        if (genericOpenBtn.count() > 0 && !isDisabled(genericOpenBtn)) {
          Utils.clickWithCursor(page, genericOpenBtn)
        }
      }

      val start = System.currentTimeMillis()
      while (System.currentTimeMillis() - start < timeoutMs) {
        if (panelVisible) {
          normalizeResultCaptureArea(page)
          expandResultPanel(page)
          assertPanelHasContent(page)
          return
        }
        if (hasCompilationErrorVisible) {
          // Surface the compilation error as a hard failure so AutoFix's LLM gets
          // a chance to patch the offending column / type. Previously this returned
          // silently and the saved video was just an error banner.
          val msg = readVisibleText(staticError).orElse(readVisibleText(compilationError)).getOrElse("compilation error")
          throw new RuntimeException(s"Workflow compilation error: $msg")
        }
        page.waitForTimeout(Delays.Settle)
      }

      if (hasCompilationErrorVisible) {
        val msg = readVisibleText(staticError).orElse(readVisibleText(compilationError)).getOrElse("compilation error")
        throw new RuntimeException(s"Workflow compilation error: $msg")
      }

      throw new RuntimeException("Result panel did not open in time.")
    }
  })

  private def readVisibleText(loc: Locator): Option[String] =
    try {
      if (loc.count() > 0 && loc.isVisible()) Option(loc.innerText()).map(_.trim).filter(_.nonEmpty)
      else None
    } catch { case _: Exception => None }

  // Sanity check the result panel actually rendered something. Catches the case
  // where openResultPanel succeeded structurally (panel is visible) but Texera's
  // workflow execution produced no output — leaving an empty/loading/error pane.
  //
  // Selectors are derived from frontend/.../result-panel.component.html:
  //   - empty:   `frameComponentConfigs.size === 0` → renders `<h4>No results
  //              available to display.</h4>` inside the tab content.
  //   - error:   workflow runtime errors are surfaced via the `texera-error-frame`
  //              component, whose `.error-message` paragraphs carry the detail text.
  //
  // Conservative: only fails on clear no-result / explicit-error signals; absence
  // of these is treated as "panel has content" rather than diving into operator-
  // specific positive checks (table rows / chart svg).
  private def assertPanelHasContent(page: Page): Unit = {
    val content = page.locator("#result-container #content").first()
    if (content.count() == 0) return

    // Result frames mount asynchronously; give them a brief moment.
    page.waitForTimeout(Delays.Settle)

    val emptyHeading = content.getByText("No results available to display", new Locator.GetByTextOptions().setExact(false)).first()
    val emptyVisible =
      try emptyHeading.count() > 0 && emptyHeading.isVisible()
      catch { case _: Exception => false }
    if (emptyVisible) {
      throw new RuntimeException("Result panel rendered 'No results available to display'; workflow produced no output frames.")
    }

    val errorMessages = content.locator("texera-error-frame .error-message")
    val errCount =
      try errorMessages.count()
      catch { case _: Exception => 0 }
    if (errCount > 0) {
      val firstMsg =
        try Option(errorMessages.first().innerText()).map(_.trim).filter(_.nonEmpty)
        catch { case _: Exception => None }
      throw new RuntimeException(
        s"Result panel surfaced workflow error(s) via texera-error-frame: ${firstMsg.getOrElse("<no text>")}"
      )
    }
  }
}
