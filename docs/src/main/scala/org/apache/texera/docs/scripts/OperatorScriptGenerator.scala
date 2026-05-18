/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.docs.scripts

import org.apache.texera.amber.operator.metadata.{GroupInfo, OperatorGroupConstants, OperatorMetadata, OperatorMetadataGenerator}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/**
 * Generates per-operator script files under:
 * docs/src/main/scala/org/apache/texera/docs/scripts/operators/
 */
object OperatorScriptGenerator {

  private val outputDir: Path = Paths.get(
    "docs", "src", "main", "scala", "org", "apache", "texera", "docs", "scripts", "operators"
  )

  private lazy val groupPathByName: Map[String, Seq[String]] =
    buildGroupPathMap(OperatorGroupConstants.OperatorGroupOrderList)

  // Data Input operators that use the "Select File" modal flow (DatasetControllerBuilder).
  // FileScan + TextInput are intentionally NOT here: their fileName / textInput fields are
  // plain text inputs (no modal), so they're handled solely via fillFieldJsonValues from
  // operator-field-values.json (FileScan stores a full /texera/.../path; TextInput stores
  // raw text content).
  private val datasetSelectionOperatorTypes: Set[String] = Set(
    "ArrowSource",
    "CSVFileScan",
    "CSVOldFileScan",
    "JSONLFileScan"
  )

  def main(args: Array[String]): Unit = {
    Files.createDirectories(outputDir)

    val ops = OperatorMetadataGenerator.allOperatorMetadata.operators
      .sortBy(_.additionalMetadata.userFriendlyName)

    ops.foreach { m =>
      val fileName = s"${classNameFor(m)}.scala" // "BarChartScript.scala"
      val groupPath = groupPathByName.getOrElse(m.additionalMetadata.operatorGroupName, Seq(m.additionalMetadata.operatorGroupName))
      val targetDir = groupPath.foldLeft(outputDir) { case (dir, segment) =>
        dir.resolve(sanitizePath(segment))
      } // operators/Visualization/Basic/BarChartScript.scala
      Files.createDirectories(targetDir)
      val content = renderScript(m)
      Files.write(targetDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8))
    }

    val registryContent = renderRegistry(ops)
    Files.write(outputDir.resolve("OperatorScriptRegistry.scala"), registryContent.getBytes(StandardCharsets.UTF_8))

    println(s"Wrote ${ops.size} scripts to ${outputDir.toAbsolutePath}")
  }

  private def renderScript(m: OperatorMetadata): String = {
    val className = classNameFor(m) // "BarChartScript"
    val operatorName = escape(m.additionalMetadata.userFriendlyName) // "Bar Chart"
    val operatorType = escape(m.operatorType) // "BarChart"
    val category = escape(m.additionalMetadata.operatorGroupName) // "Visualization Basic"

    val groupPath = groupPathByName.getOrElse(m.additionalMetadata.operatorGroupName, Seq.empty)
    val isVisualization = groupPath.contains(OperatorGroupConstants.VISUALIZATION_GROUP)
    val isML = groupPath.contains(OperatorGroupConstants.MACHINE_LEARNING_GROUP)
    val isDataInput = groupPath.contains(OperatorGroupConstants.INPUT_GROUP)
    val isDataCleaning = groupPath.contains(OperatorGroupConstants.CLEANING_GROUP)
    val needsDatasetSelection = isDataInput && datasetSelectionOperatorTypes.contains(m.operatorType)

    val outputFileName = s"${slugify(m.additionalMetadata.userFriendlyName)}_demo.webm" // "bar-chart_demo.webm"

    val autoFillKeys = if (isVisualization) requiredAutofillKeys(m) else Seq.empty

    val autoFillLiteral =
      if (autoFillKeys.nonEmpty) autoFillKeys.map(k => s""""$k"""").mkString("Seq(", ", ", ")")
      else "Seq.empty[String]"
    val hasNoInputs = m.additionalMetadata.inputPorts.isEmpty
    val hasMultipleInputs = m.additionalMetadata.inputPorts.size >= 2
    // True for Data Cleaning operators that declare ≥2 inputs in their OpDesc.
    // Despite the historical workflowJsonDir_join naming, this covers BOTH:
    //   - Relational joins: CartesianProduct, HashJoin, IntervalJoin (group "Join")
    //   - 2-input set ops:  Intersect, Difference, SymmetricDifference (group "Set")
    // Union has only 1 declared port (extra inputs are added dynamically) so it
    // does NOT fall into this branch — it follows the regular 1-input path.
    val isTwoInputCleaning = isDataCleaning && hasMultipleInputs

    // ── HARDCODED REFERENCES ───────────────────────────────────────────────
    // These string literals reference operatorID prefixes baked into the
    // sample_*.json workflow templates. If a template is renamed or its
    // operator IDs change, these must be updated in lockstep.
    //
    //   AnchorCsv    — sample.json / sample_tree.json — single CSVFileScan
    //                  ID prefix used for visualization + non-join data cleaning ops.
    //   AnchorSplit  — sample_ML.json — Split operator ID prefix.
    //                  ML predictor ops drag next to it; ML 2-input ops also
    //                  pull a second input from Split.output-1 (testing port).
    //
    // Join ops can't use AnchorCsv because sample_join.json contains TWO
    // CSVFileScan nodes (left + right) — the prefix would match either.
    // Join scripts use canvasPosition + explicit connectOperators("...-left/right", ...).
    // ───────────────────────────────────────────────────────────────────────
    val AnchorCsv = "CSVFileScan-operator-"
    val AnchorSplit = "Split-operator-"

    val dragNextToArg =
      if (isTwoInputCleaning) {
        // 2-input data cleaning ops (joins + set ops) use sample_join.json (CSV →
        // Split, single source). Both Split outputs carry the SAME schema, which
        // satisfies operators that require attribute-name + type alignment between
        // their two inputs (HashJoin/IntervalJoin), and trivially supports the rest
        // (CartesianProduct + Intersect/Difference/SymmetricDifference). Wiring:
        // anchor on Split, port 0 → port 0, and additional Split.output-1 → port 1.
        // No yOffset — drop the operator at the default position right of Split.
        s""", dragNextTo = Some("$AnchorSplit"), autoConnectToAnchor = true, connectAdditionalFrom = Some("$AnchorSplit"), connectAdditionalFromPortIndex = 1, connectAdditionalToInputIndex = Some(1)"""
      } else if (isVisualization || isDataCleaning) {
        s""", dragNextTo = Some("$AnchorCsv")"""
      } else if (isML && hasMultipleInputs) {
        s""", dragNextTo = Some("$AnchorSplit"), autoConnectToAnchor = true, connectAdditionalFrom = Some("$AnchorSplit"), connectAdditionalFromPortIndex = 1, connectAdditionalToInputIndex = Some(1)"""
      } else if (isML) {
        s""", dragNextTo = Some("$AnchorSplit"), autoConnectToAnchor = true"""
      } else if (hasNoInputs) {
        ", canvasPosition = (0.30, 0.30)"
      } else ""
    // Operators tagged "dataset": "test2" in operator-field-values.json need a workflow
    // that loads movie_tree.csv (featured-dataset-movie-2/v2). Use sample_tree.json for those.
    val usesTestTreeDataset = OperatorFieldValues.datasetKey(m.operatorType) == "test2"
    val workflowJsonDirRef =
      if (isTwoInputCleaning) "TestDataConfig.workflowJsonDir_join"
      else if (isML) "TestDataConfig.workflowJsonDir_ML"
      else if (usesTestTreeDataset) "TestDataConfig.workflowJsonDir_tree"
      else "TestDataConfig.workflowJsonDir"

    // Each block returns its content WITHOUT trailing newline; we join with "\n\n"
    // to get exactly one blank line between sections. Avoids the double-blank-line
    // and indentation-fragility bugs of the previous string-template approach.

    val navigationBlock =
      if (hasNoInputs) {
        """    new NavigationControllerBuilder(ctx)
          |      .createNewWorkflow()
          |      .cleanWorkflow()
          |      .execute()""".stripMargin
      } else if (isVisualization || isDataCleaning || isML) {
        s"""    new NavigationControllerBuilder(ctx)
           |      .createNewWorkflow()
           |      .importWorkflow($workflowJsonDirRef)
           |      .execute()""".stripMargin
      } else {
        """    new NavigationControllerBuilder(ctx)
          |      .createNewWorkflow()
          |      .cleanWorkflow()
          |      .execute()""".stripMargin
      }

    val operatorBlock =
      s"""    new OperatorControllerBuilder(ctx)
         |      .insertViaDrag(operatorName, operatorType = Some(operatorType)$dragNextToArg)
         |      .execute()""".stripMargin

    val datasetBlockOpt =
      if (needsDatasetSelection)
        // Per-op file pick: ArrowSource → movie_tree.arrow, JSONLFileScan → movie_tree.jsonl,
        // CSVFileScan/CSVOldFileScan → movies.csv (or whatever JSON says). Fallback to
        // dataset.files.head if operator-field-values.json has no fileName for this op.
        Some(
          """    val dataset = TestDataConfig.datasets("test1")
            |    val datasetBuilder = new DatasetControllerBuilder(ctx)
            |      .datasetName(dataset.name)
            |      .datasetVersion(dataset.version)
            |    OperatorFieldValues.fieldValue(operatorType, "fileName")
            |      .orElse(dataset.files.headOption)
            |      .foreach(datasetBuilder.file)
            |    datasetBuilder.execute()""".stripMargin
        )
      else None

    // Form-fill block: the autoFill `else if` is inlined here as a complete
    // Scala statement (rather than appended via string concatenation) so the
    // shape of the generated code is unambiguous regardless of branch state.
    val formFillBlock =
      if (autoFillKeys.nonEmpty)
        s"""    val configured = OperatorFieldValues.typedValues(operatorType)
           |    if (configured.nonEmpty) {
           |      new FormControllerBuilder(ctx)
           |        .fillFieldJsonValues(configured)
           |        .execute()
           |    } else if ($autoFillLiteral.nonEmpty) {
           |      new FormControllerBuilder(ctx)
           |        .autoFillFields($autoFillLiteral)
           |        .execute()
           |    }""".stripMargin
      else
        """    val configured = OperatorFieldValues.typedValues(operatorType)
          |    if (configured.nonEmpty) {
          |      new FormControllerBuilder(ctx)
          |        .fillFieldJsonValues(configured)
          |        .execute()
          |    }""".stripMargin

    val executionBlock =
      """    new ExecutionControllerBuilder(ctx)
        |      .enableViewResult()
        |      .runWorkflowAndWait()
        |      .openResultPanel()
        |      .execute()""".stripMargin

    val executeBody = (
      Seq(navigationBlock, operatorBlock) ++
        datasetBlockOpt.toSeq ++
        Seq(formFillBlock, executionBlock)
    ).mkString("\n\n") + "\n"

    s"""package org.apache.texera.docs.scripts.operators
       |
       |import org.apache.texera.docs.config.TestDataConfig
       |import org.apache.texera.docs.controllers._
       |import org.apache.texera.docs.scripts.{OperatorFieldValues, OperatorScript}
       |
       |object $className extends OperatorScript {
       |  override val operatorName: String = "$operatorName"
       |  override val category: String = "$category"
       |  override val outputFileName: String = "$outputFileName"
       |  override val operatorType: String = "$operatorType"
       |
       |  override def prepare(ctx: ControllerContext): Unit = {
       |    val user = TestDataConfig.users.headOption
       |      .getOrElse(throw new IllegalStateException("TestDataConfig.users is empty"))
       |    new LoginControllerBuilder(ctx)
       |      .login(user.username, user.password)
       |      .execute()
       |  }
       |
       |  override def execute(ctx: ControllerContext): Unit = {
       |$executeBody
       |  }
       |
       |  override def finish(ctx: ControllerContext): Unit = {}
       |}
       |""".stripMargin
  }

  private def classNameFor(m: OperatorMetadata): String = {
    val base = sanitizeIdentifier(m.operatorType)
    if (base.endsWith("Script")) base else s"${base}Script"
  }

  private def sanitizeIdentifier(raw: String): String = {
    val cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_")
    val normalized = if (cleaned.isEmpty) "Operator" else cleaned
    if (normalized.head.isDigit) s"Op$normalized" else normalized
  }

  private def slugify(s: String): String =
    s.replaceAll("\\s+", "-").replaceAll("[^a-zA-Z0-9-]", "").toLowerCase // File name: "Bar Chart" → "bar-chart"

  private def renderRegistry(ops: Seq[OperatorMetadata]): String = {
    val names = ops.map(classNameFor).distinct.sorted
    val allList = if (names.nonEmpty) names.mkString("    ", ",\n    ", "") else ""
    s"""package org.apache.texera.docs.scripts.operators
       |
       |import org.apache.texera.docs.scripts.OperatorScript
       |
       |object OperatorScriptRegistry {
       |  val all: Seq[OperatorScript] = Seq(
       |$allList
       |  )
       |
       |  def byGroup(groupName: String): Seq[OperatorScript] =
       |    all.filter(_.category == groupName)
       |
       |  def byOperatorName(name: String): Seq[OperatorScript] = {
       |    val n = normalize(name)
       |    all.filter(s => normalize(s.operatorName) == n || normalize(s.getClass.getSimpleName) == n)
       |  }
       |
       |  private def normalize(s: String): String =
       |    s.toLowerCase.replaceAll("[^a-z0-9]", "")
       |}
       |""".stripMargin
  }

  private def sanitizePath(raw: String): String = {
    raw.trim.replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9-_]", "")
  }

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  // Returns the union of (a) schema "required" fields and (b) properties tagged
  // with an "autofill" annotation. Used to populate the autoFillFields(...) fallback
  // in generated visualization scripts so the form has at least placeholder values
  // when operator-field-values.json has no entry for the operator.
  private def requiredAutofillKeys(m: OperatorMetadata): Seq[String] = {
    val schema = m.jsonSchema

    val required: Seq[String] = {
      val node = schema.path("required")
      if (!node.isArray) Seq.empty
      else node.elements().asScala.map(_.asText("")).filter(_.nonEmpty).toSeq
    }

    val autofillTagged: Seq[String] = {
      val properties = schema.path("properties")
      if (!properties.isObject) Seq.empty
      else properties.fields().asScala.flatMap { entry =>
        val tag = entry.getValue.path("autofill").asText("").trim
        if (tag.isEmpty) None else Some(entry.getKey)
      }.toSeq
    }

    (required ++ autofillTagged).distinct
  }

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
