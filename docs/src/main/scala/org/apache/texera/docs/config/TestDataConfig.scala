package org.apache.texera.docs.config

case class UserAccount(username: String, password: String)
case class Dataset(name: String, version: String, files: Seq[String])
case class Workflow(id: String, name: String)
case class UiConfig(
                     recordWidth: Int,
                     recordHeight: Int,
                     slowMo: Int,
                     propertyPanelResizeHeight: Double,
                     operatorPosX: Double,
                     operatorPosY: Double
                   )

object TestDataConfig {
  // Account
  val baseUrl = "http://localhost:4200"
  val users = Seq(
    UserAccount("texera", "texera"),
  )

  // Path to an exported workflow JSON used by NavigationControllerBuilder.importWorkflow
  val workflowJsonDir: String = "docs/src/main/scala/org/apache/texera/docs/config/sample.json"

  // Dataset
  val datasets = Map(
    "test1" -> Dataset(
      name = "test1",
      version = "v1",
      files = Seq("IMDb_All_Genres_etf_clean1.csv")
    ),
  )

  // Workflow
  val workflows = Map(
    "WorkflowA" -> Workflow("8", "Workflow A - Data Input Demo"),
    "WorkflowB" -> Workflow("13", "Workflow B - Machine Learning Demo"),
    "Workflow10" -> Workflow("10", "Workflow 10 - Visualization Demo"),
  )

  // Css
  val uiConfig = UiConfig(
    recordWidth = 1366,
    recordHeight = 768,
    //1280, 720
    slowMo = 400,
    propertyPanelResizeHeight = 300.0,
    operatorPosX = 0.33,
    operatorPosY = 0.4
  )

  // Video output directory
  val videoOutputDir = "docs/generated/videos"
}
