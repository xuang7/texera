package org.apache.texera.docs.autofix

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

object LLMPatchProposer {

  private val mapper = new ObjectMapper()
  private val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .build()

  private def apiKey: String = sys.env.getOrElse("ANTHROPIC_API_KEY", "").trim
  private def model: String = sys.env.getOrElse("ANTHROPIC_MODEL", "claude-sonnet-4-6").trim
  private def baseUrl: String = sys.env.getOrElse("ANTHROPIC_BASE_URL", "https://api.anthropic.com").trim.stripSuffix("/")
  private def maxTokens: Int = sys.env.getOrElse("ANTHROPIC_MAX_TOKENS", "1500").toInt

  private val systemPrompt: String =
    """You fix Texera operator config entries in operator-field-values.json so a Playwright workflow can record a demo video successfully.
      |
      |You are given:
      |  1. The operator type, the failing step name, the exception class + message.
      |  2. Browser console errors and any Texera workflow error text.
      |  3. The current JSON config object for this operator.
      |  4. The operator's JSON schema (properties, attributeTypeRules, required).
      |  5. The dataset name and its column schema (name + type).
      |  6. Optionally: a validator-rejection message from a prior attempt to patch.
      |
      |Return ONLY a JSON object, no prose, no markdown fences:
      |{
      |  "operatorType": "<operatorType>",
      |  "reasoning": "<one short sentence>",
      |  "confidence": "high" | "medium" | "low",
      |  "diff": [ {"op": "replace"|"add"|"remove", "path": "/key/...", "value": <any json>} ]
      |}
      |
      |Rules:
      | - Paths are RFC 6902 pointers RELATIVE to the operator's config object (e.g. "/value" not "/operators/BarChart/value").
      | - Prefer the minimal diff that fixes the failure. Do not rewrite unrelated keys.
      | - When picking a column, pick one that exists in the dataset schema and whose type matches the attributeTypeRules enum if present.
      | - Never invent column names. Never reference columns from a different dataset than the one given.
      | - If a value is an array of objects (e.g. predicates, attributes), patch the inner object key with paths like "/predicates/0/attribute".
      | - "remove" ops omit "value".
      | - Use "add" (not "replace") when the target path doesn't exist yet. RFC 6902 "replace" fails on
      |   missing keys; "add" creates missing intermediate objects. Notably, "/_controllerHints/*" paths
      |   are always missing on the first patch — always emit them as "add".
      |
      |Controller hint — UI label override, complementary to value patches:
      | - "/_controllerHints/formFieldLabels/<configKey>" (string): exact UI label of a form field when
      |   "Field not found: <configKey>" appears, or a label-resolution timeout (waiting for nz-select / dropdown).
      |   The configKey matches the key in this operator's config object. Never set to an empty string.
      |
      |WHEN to prefer the hint over a value patch:
      | - TimeoutError waiting for ".ant-select-dropdown" / "combobox" / "nz-select" usually means the controller
      |   is looking at the wrong formly-field. Try /_controllerHints/formFieldLabels/<key> with the exact label.
      | - "Failed to fill sub-field '<key>' with value '<v>' in array field '<arr>'" — controller-side bug, not a
      |   value error. Do NOT oscillate "true"/true/"false"/false. Try a formFieldLabels hint for <key>, or as a
      |   last resort drop the array entries (e.g., "replace /<arr> = []") to skip the row.
      |
      |WHEN to stick with value patches:
      | - "Column 'X' not found in dataset" / "expected type Y but got Z" — value-level, fix the column or type.
      | - "Workflow execution did not finish within Nms" with Run button = 'Pause' — the workflow is genuinely
      |    stuck on the backend (slow algorithm / too much data). Try smaller columns / smaller degree / disable
      |    countVectorizer. Hints will NOT help here.
      | - Texera workflow error text mentions an explicit value problem (e.g., "non-numeric column in regression").
      |
      |If the same patch shape failed in a prior attempt (see validator-rejection line below), change strategy —
      |do not propose a near-identical diff.
      |""".stripMargin

  def proposePatch(
    fc: FailureContext,
    priorValidatorError: Option[String] = None
  ): Either[String, (PatchEnvelope, TokenUsage)] = {
    if (apiKey.isEmpty) return Left("ANTHROPIC_API_KEY not set")

    val userMessage = buildUserMessage(fc, priorValidatorError)

    val body = mapper.createObjectNode()
    body.put("model", model)
    body.put("max_tokens", maxTokens)

    // System prompt sent as a content block with cache_control so Anthropic caches
    // the ~1.5k-token prompt across calls (cached reads are 10% of input price).
    val systemArr = mapper.createArrayNode()
    val systemBlock = mapper.createObjectNode()
    systemBlock.put("type", "text")
    systemBlock.put("text", systemPrompt)
    val cacheCtl = mapper.createObjectNode()
    cacheCtl.put("type", "ephemeral")
    systemBlock.set[com.fasterxml.jackson.databind.node.ObjectNode]("cache_control", cacheCtl)
    systemArr.add(systemBlock)
    body.set[com.fasterxml.jackson.databind.node.ObjectNode]("system", systemArr)

    val messages = mapper.createArrayNode()
    val userMsg = mapper.createObjectNode()
    userMsg.put("role", "user")
    userMsg.put("content", userMessage)
    messages.add(userMsg)
    body.set("messages", messages)

    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/v1/messages"))
      .timeout(Duration.ofSeconds(60))
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
      .build()

    val resp =
      try httpClient.send(req, HttpResponse.BodyHandlers.ofString())
      catch { case e: Exception => return Left(s"HTTP error: ${e.getMessage}") }

    if (resp.statusCode() / 100 != 2) {
      return Left(s"Claude API status=${resp.statusCode()}: ${resp.body().take(500)}")
    }

    parseResponse(resp.body(), fc.operatorType)
  }

  private def buildUserMessage(fc: FailureContext, priorError: Option[String]): String = {
    val rf = fc.runFailure
    val sb = new StringBuilder
    sb.append(s"Operator type: ${rf.operatorType}\n")
    sb.append(s"Operator name: ${rf.operatorName}\n")
    sb.append(s"Category: ${rf.category}\n")
    sb.append(s"Failing step: ${rf.stepName}\n")
    sb.append(s"Exception: ${rf.exceptionClass}: ${rf.exceptionMessage}\n")
    if (rf.stackTraceHead.nonEmpty) sb.append(s"Stack head:\n${rf.stackTraceHead}\n")
    if (rf.consoleErrors.nonEmpty) sb.append(s"Browser console errors:\n${rf.consoleErrors.mkString("\n")}\n")
    rf.workflowErrorText.foreach(t => sb.append(s"Texera workflow error: $t\n"))

    sb.append(s"\nDataset key: ${fc.datasetKey}\n")
    sb.append(s"Dataset schema:\n${prettyJson(fc.datasetSchema)}\n")

    sb.append(s"\nCurrent operator config:\n${prettyJson(fc.currentConfig)}\n")

    sb.append(s"\nOperator JSON schema (relevant subset):\n${prettyJson(trimSchema(fc.operatorSchema))}\n")

    priorError.foreach { e =>
      sb.append(s"\nA previous patch was rejected by the validator with: $e\n")
      sb.append("Propose a different patch.\n")
    }

    sb.toString
  }

  private def prettyJson(node: JsonNode): String =
    try mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
    catch { case _: Exception => node.toString }

  /** Drop bulky fields from the operator schema (descriptions, $$refs, ui hints) to keep tokens down. */
  private def trimSchema(schema: JsonNode): JsonNode = {
    if (!schema.isObject) return schema
    val out = mapper.createObjectNode()
    val keepTop = Set("title", "type", "required", "properties", "attributeTypeRules")
    schema.fields().asScala.foreach { e =>
      if (keepTop.contains(e.getKey)) out.set[com.fasterxml.jackson.databind.node.ObjectNode](e.getKey, trimProps(e.getValue))
    }
    out
  }

  private def trimProps(node: JsonNode): JsonNode = {
    if (!node.isObject) return node
    val obj = mapper.createObjectNode()
    val drop = Set("description", "default", "examples", "$$ref")
    node.fields().asScala.foreach { e =>
      if (!drop.contains(e.getKey)) {
        if (e.getValue.isObject) obj.set[com.fasterxml.jackson.databind.node.ObjectNode](e.getKey, trimProps(e.getValue))
        else obj.set[com.fasterxml.jackson.databind.node.ObjectNode](e.getKey, e.getValue)
      }
    }
    obj
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

  private def parseResponse(body: String, operatorType: String): Either[String, (PatchEnvelope, TokenUsage)] = {
    try {
      val root = mapper.readTree(body)
      val usage = TokenUsage(
        input = root.path("usage").path("input_tokens").asInt(0),
        output = root.path("usage").path("output_tokens").asInt(0),
        cacheRead = root.path("usage").path("cache_read_input_tokens").asInt(0),
        cacheCreation = root.path("usage").path("cache_creation_input_tokens").asInt(0)
      )

      val contentArr = root.path("content")
      if (!contentArr.isArray || contentArr.size() == 0)
        return Left(s"Claude API returned no content: ${body.take(300)}")
      val text = contentArr.elements().asScala
        .filter(_.path("type").asText("") == "text")
        .map(_.path("text").asText(""))
        .mkString("\n")
        .trim
      if (text.isEmpty) return Left("Claude API returned empty text block")

      val jsonText = extractJsonBlock(text)
      val patchRoot = mapper.readTree(jsonText)
      val opType = patchRoot.path("operatorType").asText(operatorType)
      val reasoning = patchRoot.path("reasoning").asText("")
      val confidence = patchRoot.path("confidence").asText("medium")

      val diffNode = patchRoot.path("diff")
      if (!diffNode.isArray) return Left(s"Expected 'diff' to be a JSON array; got: $jsonText")

      val ops = diffNode.elements().asScala.map { n =>
        val op = n.path("op").asText("")
        val path = n.path("path").asText("")
        val valueOpt = if (n.has("value")) Some(n.path("value")) else None
        PatchOp(op, path, valueOpt)
      }.toSeq.filter(p => p.op.nonEmpty && p.path.nonEmpty)

      Right((PatchEnvelope(opType, reasoning, confidence, ops), usage))
    } catch {
      case e: Exception => Left(s"Failed to parse Claude response: ${e.getMessage}; body=${body.take(300)}")
    }
  }
}
