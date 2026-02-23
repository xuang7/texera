package org.apache.texera.docs.scripts

import org.apache.texera.amber.operator.metadata.OperatorGroupConstants
import org.apache.texera.docs.controllers.ControllerStep
import org.apache.texera.docs.orchestrator.OperatorScenario
import org.apache.texera.docs.scripts.operators.OperatorScriptRegistry

object VideoScenarioScripts {

  // Scripts -> Runner, get all scenarios
  def dataInputScenarios: Seq[OperatorScenario] =
    OperatorScriptRegistry.byGroup(OperatorGroupConstants.INPUT_GROUP).map(toScenario)

  def visualizationBasicScenarios: Seq[OperatorScenario] =
    OperatorScriptRegistry.byGroup(OperatorGroupConstants.VISUALIZATION_BASIC_GROUP).map(toScenario)

  val allScenarios: Seq[OperatorScenario] = visualizationBasicScenarios
  //   val allScenarios = visualizationBasicScenarios ++ dataInputScenarios ++

  private def toScenario(script: OperatorScript): OperatorScenario =
    OperatorScenario(
      operatorName = script.operatorName,
      category = script.category,
      workflowKey = script.workflowKey,
      steps = Seq(
        ControllerStep(s"Prepare: ${script.operatorName}")(script.prepare),
        ControllerStep(s"Execute: ${script.operatorName}")(script.execute),
        ControllerStep(s"Finish: ${script.operatorName}")(script.finish) // Empty for now
      ),
      outputFileName = script.outputFileName
    )
}
