// config/TestDataConfig.scala
package org.apache.texera.docs.config

case class UserAccount(username: String, password: String)
case class Dataset(name: String, version: String, files: Seq[String])
case class Workflow(id: String, name: String)
case class UiConfig(
                     recordWidth: Int,
                     recordHeight: Int,
                     slowMo: Int,
                     propertyPanelDragLeft: Double,
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
    //Fake one
    "mini" -> Dataset(
      name = "mini",
      version = "v1",
      files = Seq("mini_data.csv")
    ),
    "lakers" -> Dataset(
      name = "lakers",
      version = "v1",
      files = Seq("lakers_stats.csv")
    )
  )

  // Workflow
  val workflows = Map(
    "WorkflowA" -> Workflow("8", "Workflow A - Data Input Demo"),
    "WorkflowB" -> Workflow("9", "Workflow B - Machine Learning Demo"),
    "WorkflowC" -> Workflow("9", "Workflow B - Visualization Demo")
  )

  // Css
  val uiConfig = UiConfig(
    recordWidth = 1280,
    recordHeight = 720,
    //1280, 720
    slowMo = 400,
    propertyPanelDragLeft = 400.0,
    propertyPanelResizeHeight = 300.0,
    operatorPosX = 0.33,
    operatorPosY = 0.4
  )

  // Video output directory
  val videoOutputDir = "docs/generated/videos"
}