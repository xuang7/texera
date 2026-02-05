// orchestrator/VideoRunner.scala
package org.apache.texera.docs.orchestrator

import com.microsoft.playwright._
import org.apache.texera.docs.controllers.UiController
import org.apache.texera.docs.config.TestDataConfig

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

case class OperatorScenario(
                             operatorName: String,
                             category: String,
                             workflowKey: String,
                             controllers: Seq[UiController],
                             outputFileName: String
                           )

class VideoRunner {

  def generateVideos(scenarios: Seq[OperatorScenario]): Unit = {
    val videoDir = Paths.get(TestDataConfig.videoOutputDir)
    Files.createDirectories(videoDir)

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  Generating ${scenarios.length} operator videos")
    println(s"╚════════════════════════════════════════════════════╝\n")

    scenarios.foreach { scenario =>
      println(s"\n→ Generating: ${scenario.operatorName}")
      generateSingleVideo(scenario, videoDir)
    }

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  All videos saved to: $videoDir")
    println(s"╚════════════════════════════════════════════════════╝\n")
  }

  private def generateSingleVideo(
                                   scenario: OperatorScenario,
                                   videoDir: java.nio.file.Path
                                 ): Unit = {

    val playwright = Playwright.create()
    val browser = playwright.chromium().launch(
      new BrowserType.LaunchOptions()
        .setHeadless(false)
        .setSlowMo(TestDataConfig.uiConfig.slowMo)
    )

    val context = browser.newContext(
      new Browser.NewContextOptions()
        .setRecordVideoDir(videoDir)
        .setRecordVideoSize(
          TestDataConfig.uiConfig.recordWidth,
          TestDataConfig.uiConfig.recordHeight
        )
        .setViewportSize(
          TestDataConfig.uiConfig.recordWidth,
          TestDataConfig.uiConfig.recordHeight
        )
    )

    val page = context.newPage()
    val video = page.video()

    try {
      // Execute all controllers
      scenario.controllers.foreach { controller =>
        controller.execute(page)
        page.waitForTimeout(500)
      }

      // Final wait
      page.waitForTimeout(2000)

      println(s"  ✓ Completed: ${scenario.operatorName}")

    } catch {
      case e: Exception =>
        println(s"  ✗ ERROR: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      var videoPath: java.nio.file.Path = null
      try {
        context.close()
        if (video != null) {
          videoPath = video.path()
        }
      } catch {
        case _: Exception =>
      } finally {
        browser.close()
        playwright.close()
      }

      if (videoPath != null) {
        renameVideoFile(videoPath, scenario.outputFileName)
      } else {
        println(s"Warning: No video file found for ${scenario.operatorName}")
      }
    }
  }

  private def renameVideoFile(videoPath: java.nio.file.Path, newName: String): Unit = {
    try {
      val targetPath = videoPath.getParent.resolve(newName)
      Files.move(videoPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      println(s"Saved as: $newName")
    } catch {
      case e: Exception =>
        println(s"Warning: Could not rename video - ${e.getMessage}")
    }
  }
}
