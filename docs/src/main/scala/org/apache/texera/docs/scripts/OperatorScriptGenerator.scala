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

  private val datasetSelectionOperatorTypes: Set[String] = Set(
    "ArrowSource",
    "CSVFileScan",
    "CSVOldFileScan",
    "FileScan",
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

    val workflowKey =
      if (isVisualization) "Workflow10"
      else if (isML) "WorkflowB"
      else if (isDataCleaning) "WorkflowA"
      else "WorkflowA"
    val outputFileName = s"${slugify(m.additionalMetadata.userFriendlyName)}_demo.webm" // "bar-chart_demo.webm"

    val autoFillKeys = if (isVisualization) OperatorFieldPlanner.requiredAutofillKeys(m) else Seq.empty

    val autoFillLiteral =
      if (autoFillKeys.nonEmpty) autoFillKeys.map(k => s""""$k"""").mkString("Seq(", ", ", ")")
      else "Seq.empty[String]"
    val hasMultipleInputs = m.additionalMetadata.inputPorts.size >= 2
    val dragNextToArg =
      if (isVisualization || isDataCleaning) {
        ", dragNextTo = Some(\"CSVFileScan-operator-\")"
      } else if (isML && hasMultipleInputs) {
        ", dragNextTo = Some(\"Split-operator-\"), yOffset = -110.0, autoConnectToAnchor = true, fromPortIndex = 0, toPortIndex = 0, connectAdditionalFrom = Some(\"Projection-operator-\"), connectAdditionalFromPortIndex = 0, connectAdditionalToInputIndex = Some(1)"
      } else if (isML) {
        ", dragNextTo = Some(\"Split-operator-\"), yOffset = -110.0, autoConnectToAnchor = true, fromPortIndex = 0, toPortIndex = 0"
      } else if (isDataInput) {
        ", canvasPosition = (0.20, 0.30)"
      } else ""
    val workflowJsonDirRef =
      if (isML) "TestDataConfig.workflowJsonDir_ML"
      else "TestDataConfig.workflowJsonDir"

    val navigationBootstrap =
      if (isVisualization || isDataCleaning || isML) {
        s"""    new NavigationControllerBuilder(ctx)
           |      .openWorkflow(workflow.id, workflow.name)
           |      .importWorkflow($workflowJsonDirRef)
           |      .execute()
           |""".stripMargin
      } else {
        """    new NavigationControllerBuilder(ctx)
          |      .openWorkflow(workflow.id, workflow.name)
          |      .cleanWorkflow()
          |      .execute()
          |""".stripMargin
      }

    val executeBody =
      s"""    val workflow = TestDataConfig.workflows.getOrElse(
         |      workflowKey,
         |      throw new IllegalArgumentException(s"Workflow key not found in TestDataConfig: $$workflowKey")
         |    )
         |
         |$navigationBootstrap
         |
         |    new OperatorControllerBuilder(ctx)
         |      .insertViaDrag(operatorName, operatorType = Some(operatorType)$dragNextToArg)
         |      .execute()
         |
         |${if (needsDatasetSelection)
               """    val dataset = TestDataConfig.datasets("test1")
                 |    val datasetBuilder = new DatasetControllerBuilder(ctx)
                 |      .datasetName(dataset.name)
                 |      .datasetVersion(dataset.version)
                 |    dataset.files.headOption.foreach(datasetBuilder.file)
                 |    datasetBuilder.execute()
                 |
                 |""".stripMargin
             else ""}
         |    val configured = OperatorFieldValues.typedValues(operatorType)
         |    if (configured.nonEmpty) {
         |      new FormControllerBuilder(ctx)
         |        .fillFieldJsonValues(configured)
         |        .execute()
         |    } else if ($autoFillLiteral.nonEmpty) {
         |      new FormControllerBuilder(ctx)
         |        .autoFillFields($autoFillLiteral)
         |        .execute()
         |    }
         |
         |    new ExecutionControllerBuilder(ctx)
         |      .runWorkflowAndWait()
         |      .openResultPanel()
         |      .execute()
         |""".stripMargin

    s"""package org.apache.texera.docs.scripts.operators
       |
       |import org.apache.texera.docs.config.TestDataConfig
       |import org.apache.texera.docs.controllers._
       |import org.apache.texera.docs.scripts.{OperatorFieldValues, OperatorScript}
       |
       |object $className extends OperatorScript {
       |  override val operatorName: String = "$operatorName"
       |  override val category: String = "$category"
       |  override val workflowKey: String = "$workflowKey"
       |  override val outputFileName: String = "$outputFileName"
       |  private val operatorType: String = "$operatorType"
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
