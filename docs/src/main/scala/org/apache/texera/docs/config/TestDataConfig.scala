// config/TestDataConfig.scala
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

  // Dataset
  val datasets = Map(
    "test1" -> Dataset(
      name = "test1",
      version = "v1",
      files = Seq("IMDb_All_Genres_etf_clean1.csv")
    ),
    // Placeholder
    "test2" -> Dataset(
      name = "test2",
      version = "v1",
      files = Seq("mini_data.csv")
    )
  )

  // Workflow
  val workflows = Map(
    "WorkflowA" -> Workflow("8", "Workflow A - Data Input Demo"),
    "WorkflowB" -> Workflow("13", "Workflow B - Machine Learning Demo"),
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
