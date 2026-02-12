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
                             steps: Seq[ControllerStep],
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

    val storageState: Option[String] = None

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  Generating ${scenarios.length} operator videos")
    println(s"╚════════════════════════════════════════════════════╝\n")

    scenarios.foreach { scenario =>
      println(s"\n Generating: ${scenario.operatorName}")
      generateSingleVideo(browser, storageState, scenario, videoDir)
    }

    println(s"\n╔════════════════════════════════════════════════════╗")
    println(s"║  All videos saved to: $videoDir")
    println(s"╚════════════════════════════════════════════════════╝\n")

    browser.close()
    playwright.close()
  }

  private def generateSingleVideo(
                                   browser: Browser,
                                   storageState: Option[String],
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
    storageState.foreach(options.setStorageState)

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

//  private def loginOnce(browser: Browser): Option[String] = {
//    val context = browser.newContext(
//      new Browser.NewContextOptions()
//        .setViewportSize(
//          TestDataConfig.uiConfig.recordWidth,
//          TestDataConfig.uiConfig.recordHeight
//        )
//    )
//    val page = context.newPage()
//    try {
//      val user = TestDataConfig.users.head
//      page.navigate(TestDataConfig.baseUrl)
//      page.waitForLoadState(LoadState.NETWORKIDLE)
//      page.waitForTimeout(300)
//
//      val username = page.getByTestId("login-username")
//      val password = page.getByTestId("login-password")
//      val submit = page.getByTestId("login-submit")
//
//      if (username.count() > 0 && password.count() > 0 && submit.count() > 0) {
//        username.first().fill(user.username, new Locator.FillOptions().setTimeout(5000))
//        password.first().fill(user.password, new Locator.FillOptions().setTimeout(5000))
//        submit.first().click(new Locator.ClickOptions().setTimeout(5000))
//        page.waitForLoadState(LoadState.NETWORKIDLE)
//        page.waitForTimeout(500)
//      }
//
//      if (page.isClosed) None else Some(context.storageState())
//    } catch {
//      case e: Exception =>
//        println(s"Warning: loginOnce failed, falling back to per-scenario login. ${e.getMessage}")
//        None
//    } finally {
//      try context.close()
//      catch { case _: Exception => }
//    }
//  }

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
