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
    "docs",
    "src",
    "main",
    "scala",
    "org",
    "apache",
    "texera",
    "docs",
    "scripts",
    "operators"
  )

  private val visualizationGroups: Set[String] = Set(
    OperatorGroupConstants.VISUALIZATION_GROUP,
    OperatorGroupConstants.VISUALIZATION_BASIC_GROUP,
    OperatorGroupConstants.VISUALIZATION_STATISTICAL_GROUP,
    OperatorGroupConstants.VISUALIZATION_SCIENTIFIC_GROUP,
    OperatorGroupConstants.VISUALIZATION_FINANCIAL_GROUP,
    OperatorGroupConstants.VISUALIZATION_MEDIA_GROUP,
    OperatorGroupConstants.VISUALIZATION_ADVANCED_GROUP
  )

  private val dataCleaningGroups: Set[String] = Set(
    OperatorGroupConstants.CLEANING_GROUP,
    OperatorGroupConstants.JOIN_GROUP,
    OperatorGroupConstants.SET_GROUP,
    OperatorGroupConstants.AGGREGATE_GROUP,
    OperatorGroupConstants.SORT_GROUP
  )

  private val machineLearningGroups: Set[String] = Set(
    OperatorGroupConstants.MACHINE_LEARNING_GROUP,
    OperatorGroupConstants.ADVANCED_SKLEARN_GROUP,
    OperatorGroupConstants.SKLEARN_GROUP,
    OperatorGroupConstants.SKLEARN_TRAINING_GROUP,
    OperatorGroupConstants.HUGGINGFACE_GROUP,
    OperatorGroupConstants.MACHINE_LEARNING_GENERAL_GROUP
  )

  def main(args: Array[String]): Unit = {
    Files.createDirectories(outputDir)

    val ops = OperatorMetadataGenerator.allOperatorMetadata.operators
      .sortBy(_.additionalMetadata.userFriendlyName)

    val groupPaths = buildGroupPathMap(OperatorGroupConstants.OperatorGroupOrderList)

    ops.foreach { m =>
      val fileName = s"${classNameFor(m)}.scala"
      val groupPath = groupPaths.getOrElse(m.additionalMetadata.operatorGroupName, Seq(m.additionalMetadata.operatorGroupName))
      val targetDir = groupPath.foldLeft(outputDir) { case (dir, segment) =>
        dir.resolve(sanitizePath(segment))
      }
      Files.createDirectories(targetDir)
      val content = renderScript(m)
      Files.write(targetDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8))
    }

    val registryContent = renderRegistry(ops)
    Files.write(outputDir.resolve("OperatorScriptRegistry.scala"), registryContent.getBytes(StandardCharsets.UTF_8))

    println(s"Wrote ${ops.size} scripts to ${outputDir.toAbsolutePath}")
  }

  private def renderScript(m: OperatorMetadata): String = {
    val className = classNameFor(m)
    val operatorName = escape(m.additionalMetadata.userFriendlyName)
    val operatorType = escape(m.operatorType)
    val category = escape(m.additionalMetadata.operatorGroupName)
    val workflowKey = if (visualizationGroups.contains(m.additionalMetadata.operatorGroupName)) "Workflow10" else "WorkflowA"
    val outputFileName = s"${slugify(m.additionalMetadata.userFriendlyName)}_demo.webm"

    val isInput = m.additionalMetadata.operatorGroupName == OperatorGroupConstants.INPUT_GROUP
    val isVisualization = visualizationGroups.contains(m.additionalMetadata.operatorGroupName)
    val isDataCleaning = dataCleaningGroups.contains(m.additionalMetadata.operatorGroupName)
    val isMachineLearning = machineLearningGroups.contains(m.additionalMetadata.operatorGroupName)

    val fileInputTypes = Set("FileScan", "CSVFileScan", "JSONLFileScan", "CSVOldFileScan", "ArrowSource")
    val shouldSelectFile = isInput && fileInputTypes.contains(m.operatorType)
    val isTextInput = isInput && m.operatorType == "TextInput"

    val autoFillKeys = if (isVisualization) OperatorFieldPlanner.requiredAutofillKeys(m) else Seq.empty
    val autoFillLiteral =
      if (autoFillKeys.nonEmpty) autoFillKeys.map(k => s""""$k"""").mkString("Seq(", ", ", ")")
      else "Seq.empty[String]"

    val executeBody =
      if (isVisualization) {
        s"""    val dataset = TestDataConfig.datasets("test1")
           |
           |    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag("CSV File Scan", operatorType = Some("CSVFileScan"))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |
           |    val datasetBuilder = new DatasetControllerBuilder(ctx)
           |      .datasetName(dataset.name)
           |      .datasetVersion(dataset.version)
           |    dataset.files.headOption.foreach(datasetBuilder.file)
           |    datasetBuilder.execute()
           |
           |    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag(operatorName, operatorType = Some(operatorType))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |
           |    new OperatorControllerBuilder(ctx)
           |      .connectLastTwo()
           |      .execute()
           |
           |    if ($autoFillLiteral.nonEmpty) {
           |      new FormControllerBuilder(ctx)
           |        .autoFillFields($autoFillLiteral)
           |        .execute()
           |    }
           |""".stripMargin
      } else if (isDataCleaning || isMachineLearning) {
        s"""    val dataset = TestDataConfig.datasets("test1")
           |
           |    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag("CSV File Scan", operatorType = Some("CSVFileScan"))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |
           |    val datasetBuilder = new DatasetControllerBuilder(ctx)
           |      .datasetName(dataset.name)
           |      .datasetVersion(dataset.version)
           |    dataset.files.headOption.foreach(datasetBuilder.file)
           |    datasetBuilder.execute()
           |
           |    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag(operatorName, operatorType = Some(operatorType))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |
           |    new OperatorControllerBuilder(ctx)
           |      .connectLastTwo()
           |      .execute()
           |""".stripMargin
      } else if (isInput) {
        val fileSelection =
          if (shouldSelectFile) {
            s"""    val dataset = TestDataConfig.datasets("test1")
               |    val datasetBuilder = new DatasetControllerBuilder(ctx)
               |      .datasetName(dataset.name)
               |      .datasetVersion(dataset.version)
               |    dataset.files.headOption.foreach(datasetBuilder.file)
               |    datasetBuilder.execute()
               |""".stripMargin
          } else if (isTextInput) {
            s"""    new FormControllerBuilder(ctx)
               |      .autoFillFields(Seq("textInput"), defaultText = "1,2,3,4,5,6")
               |      .execute()
               |""".stripMargin
          } else ""

        s"""    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag(operatorName, operatorType = Some(operatorType))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |
           |$fileSelection
           |""".stripMargin
      } else {
        s"""    new OperatorControllerBuilder(ctx)
           |      .insertViaDrag(operatorName, operatorType = Some(operatorType))
           |      .execute()
           |
           |    new PropertyPanelControllerBuilder(ctx)
           |      .resize()
           |      .execute()
           |""".stripMargin
      }

    s"""package org.apache.texera.docs.scripts.operators
       |
       |import org.apache.texera.docs.config.TestDataConfig
       |import org.apache.texera.docs.controllers._
       |import org.apache.texera.docs.scripts.OperatorScript
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
       |
       |    val workflow = TestDataConfig.workflows.getOrElse(
       |      workflowKey,
       |      throw new IllegalArgumentException(s"Workflow key not found in TestDataConfig: $workflowKey")
       |    )
       |    new NavigationControllerBuilder(ctx)
       |      .openWorkflow(workflow.id, workflow.name)
       |      .cleanWorkflow()
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
    s.replaceAll("\\s+", "-").replaceAll("[^a-zA-Z0-9-]", "").toLowerCase

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
