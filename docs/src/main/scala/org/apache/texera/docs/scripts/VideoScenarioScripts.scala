package org.apache.texera.docs.scripts

import org.apache.texera.amber.operator.metadata.OperatorGroupConstants
import org.apache.texera.docs.controllers.ControllerStep
import org.apache.texera.docs.orchestrator.OperatorScenario
import org.apache.texera.docs.scripts.operators.OperatorScriptRegistry

object VideoScenarioScripts {

  def dataInputScenarios: Seq[OperatorScenario] =
    OperatorScriptRegistry.byGroup(OperatorGroupConstants.INPUT_GROUP).map(toScenario _)

  def visualizationBasicScenarios: Seq[OperatorScenario] =
    OperatorScriptRegistry.byGroup(OperatorGroupConstants.VISUALIZATION_BASIC_GROUP).map(toScenario _)

  val allScenarios: Seq[OperatorScenario] = visualizationBasicScenarios

  private def toScenario(script: OperatorScript): OperatorScenario =
    OperatorScenario(
      operatorName = script.operatorName,
      category = script.category,
      workflowKey = script.workflowKey,
      steps = Seq(ControllerStep(s"Script: ${script.operatorName}")(script.run)),
      outputFileName = script.outputFileName
    )
}
