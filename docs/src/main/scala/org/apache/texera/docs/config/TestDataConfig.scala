package org.apache.texera.docs.config

case class UserAccount(username: String, password: String)
case class Dataset(name: String, version: String, files: Seq[String])
case class UiConfig(
                     recordWidth: Int,
                     recordHeight: Int,
                     slowMo: Int,
                     resultPanelHoldMs: Int,
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
  val workflowJsonDir_join: String = "docs/src/main/scala/org/apache/texera/docs/config/sample_join.json"
  val workflowJsonDir_ML: String = "docs/src/main/scala/org/apache/texera/docs/config/sample_ML.json"
  val workflowJsonDir_tree: String = "docs/src/main/scala/org/apache/texera/docs/config/sample_tree.json"

  // Dataset
  val datasets = Map(
    "test1" -> Dataset(
      name = "sample-dataset",
      version = "v1",
      files = Seq("movies.csv")
    ),
    // Tree-structured dataset used by hierarchy/network/timeline visualizations.
    // Lives in the same lakeFS repo as test1 but selects movie_tree.csv.
    "test2" -> Dataset(
      name = "sample-dataset",
      version = "v1",
      files = Seq("movie_tree.csv")
    ),
  )

  // Css
  val uiConfig = UiConfig(
    recordWidth = 1440,
    recordHeight = 900,
    //1280, 720
    slowMo = 400,
    resultPanelHoldMs = 5000,
    propertyPanelResizeHeight = 300.0,
    operatorPosX = 0.33,
    operatorPosY = 0.4
  )

  // Video output directory
  val videoOutputDir = "docs/generated/videos-demo"
}
