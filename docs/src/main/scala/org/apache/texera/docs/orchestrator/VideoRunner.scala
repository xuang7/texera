// orchestrator/VideoRunner.scala
package org.apache.texera.docs.orchestrator

import com.microsoft.playwright._
import com.microsoft.playwright.options.LoadState
import org.apache.texera.docs.config.TestDataConfig
import org.apache.texera.docs.controllers.{Controller, ControllerContext, ControllerStep}

import java.nio.file.{Files, Paths}

case class OperatorScenario(
                             operatorName: String,
                             category: String,
                             workflowKey: String,
                             steps: Seq[ControllerStep], // [Prepare, Execute, Finish]
                             outputFileName: String
                           )

class VideoRunner {

  def generateVideos(scenarios: Seq[OperatorScenario]): Unit = {
    val videoDir = Paths.get(TestDataConfig.videoOutputDir)
    Files.createDirectories(videoDir)

    val playwright = Playwright.create()
    val browser = playwright.chromium().launch(
      new BrowserType.LaunchOptions()
        .setHeadless(false)
        .setSlowMo(TestDataConfig.uiConfig.slowMo)
    )

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  Generating ${scenarios.length} operator videos")
    println(s"╚════════════════════════════════════════════════════╝\n")

    scenarios.foreach { scenario =>
      println(s"\n Generating: ${scenario.operatorName}")
      generateSingleVideo(browser, scenario, videoDir)
    }

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  All videos saved to: $videoDir")
    println(s"╚════════════════════════════════════════════════════╝\n")

    browser.close()
    playwright.close()
  }

  private def generateSingleVideo(
                                   browser: Browser,
                                   scenario: OperatorScenario,
                                   videoDir: java.nio.file.Path
                                 ): Unit = {
    val options = new Browser.NewContextOptions()
      .setRecordVideoDir(videoDir)
      .setRecordVideoSize(
        TestDataConfig.uiConfig.recordWidth,
        TestDataConfig.uiConfig.recordHeight
      )
      .setViewportSize(
        TestDataConfig.uiConfig.recordWidth,
        TestDataConfig.uiConfig.recordHeight
      )

    val context = browser.newContext(options)

    val page = context.newPage()
    val video = page.video()

    try {
      if (!page.url().startsWith(TestDataConfig.baseUrl)) {
        page.navigate(TestDataConfig.baseUrl)
        page.waitForLoadState(LoadState.NETWORKIDLE)
      }
      val ctx = new ControllerContext(page)
      new Controller(scenario.steps).execute(ctx)
      //   1. Prepare: Login
      //   2. Execute: Import → Drag → Resize → AutoFill
      //   3. Finish:  (no-op)

      // Final wait
      page.waitForTimeout(2000)
      println(s"  Completed: ${scenario.operatorName}")

    } catch {
      case e: Exception =>
        println(s"  ERROR: ${e.getMessage}")
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
