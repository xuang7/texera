package org.apache.texera.docs.scripts

import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator
import org.apache.texera.docs.config.TestDataConfig
import org.apache.texera.docs.controllers._
import org.apache.texera.docs.orchestrator.OperatorScenario

object VideoScenarioScripts {

  // Hardcoded
  private val specByOperatorType: Map[String, (String /*workflowKey*/, Seq[UiController])] = Map(
    "CSVFileScan" -> (
      "WorkflowA",
      Seq(
        new FileSelectionController(
          TestDataConfig.datasets("test1").name,
          TestDataConfig.datasets("test1").version
        )
      )
    ),
    "ArrowSource" -> (
      "WorkflowA",
      Seq(
        new FileSelectionController(
          // placeholder
          TestDataConfig.datasets("mini").name,
          TestDataConfig.datasets("mini").version
        )
      )
    )
  )

  // Data Input group
  private val targetGroups: Set[String] = Set("Data Input")

  val allScenarios: Seq[OperatorScenario] = {
    val ops = OperatorMetadataGenerator.allOperatorMetadata.operators

    val selected = ops
      .filter(m => targetGroups.contains(m.additionalMetadata.operatorGroupName))
      .sortBy(_.additionalMetadata.userFriendlyName)

    selected.map { m =>
      val opType = m.operatorType
      val uiName = m.additionalMetadata.userFriendlyName
      val groupName = m.additionalMetadata.operatorGroupName

      val (workflowKey, extraControllers) =
        specByOperatorType.getOrElse(opType, ("WorkflowA", Seq.empty))

      OperatorScenario(
        operatorName = uiName,
        category = groupName,
        workflowKey = workflowKey,
        controllers =
          Seq(
            new LoginController(TestDataConfig.users.head.username, TestDataConfig.users.head.password),
            new NavigationController(TestDataConfig.workflows(workflowKey).id, TestDataConfig.workflows(workflowKey).name),
            new OperatorInsertViaSearch(uiName), // search + enter + select node
            new PropertyPanelController()
          ) ++ extraControllers,
        outputFileName = s"${slugify(uiName)}_demo.webm"
      )
    }
  }

  private def slugify(s: String): String =
    s.replaceAll("\\s+", "-").replaceAll("[^a-zA-Z0-9-]", "").toLowerCase
}
