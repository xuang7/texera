// orchestrator/VideoRunner.scala
package org.apache.texera.docs.orchestrator

import com.microsoft.playwright._
import com.microsoft.playwright.options.LoadState
import org.apache.texera.docs.autofix.RunFailure
import org.apache.texera.docs.config.TestDataConfig
import org.apache.texera.docs.controllers.{ControllerContext, ControllerStep, LoginControllerBuilder}

import java.nio.file.{Files, Path, Paths}

case class OperatorScenario(
    operatorName: String,
    operatorType: String,
    category: String,
    steps: Seq[ControllerStep], // [Prepare, Execute, Finish]
    outputFileName: String
)

sealed trait ScenarioResult
object ScenarioResult {
  case class Success(videoPath: Path) extends ScenarioResult
  case class Failure(failure: RunFailure, videoPath: Option[Path]) extends ScenarioResult
}

class VideoRunner {

  private var playwright: Playwright = _
  private var browser: Browser = _
  private var storageState: Option[String] = None
  private var videoDir: Path = _

  def start(): Unit = {
    videoDir = Paths.get(TestDataConfig.videoOutputDir)
    Files.createDirectories(videoDir)
    playwright = Playwright.create()
    browser = playwright.chromium().launch(
      new BrowserType.LaunchOptions()
        .setHeadless(false)
        .setSlowMo(TestDataConfig.uiConfig.slowMo)
    )
    storageState = loginOnce(browser)
  }

  def close(): Unit = {
    try if (browser != null) browser.close() catch { case _: Exception => }
    try if (playwright != null) playwright.close() catch { case _: Exception => }
  }

  def generateVideos(scenarios: Seq[OperatorScenario]): Seq[ScenarioResult] = {
    start()
    try {
      println(s"\n╔════════════════════════════════════════════════════╗")
      println(s"║  Generating ${scenarios.length} operator videos")
      println(s"╚════════════════════════════════════════════════════╝\n")

      val results = scenarios.map { scenario =>
        println(s"\n Generating: ${scenario.operatorName}")
        runScenario(scenario)
      }

      println(s"\n╔════════════════════════════════════════════════════╗")
      println(s"║  All videos saved to: $videoDir")
      println(s"╚════════════════════════════════════════════════════╝\n")
      results
    } finally close()
  }

  def runScenario(scenario: OperatorScenario): ScenarioResult = {
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

    val consoleErrors = scala.collection.mutable.ArrayBuffer.empty[String]
    page.onConsoleMessage { msg =>
      if (msg.`type`() == "error") consoleErrors += Option(msg.text()).getOrElse("")
    }

    var failure: Option[(Throwable, String)] = None

    try {
      if (!page.url().startsWith(TestDataConfig.baseUrl)) {
        page.navigate(TestDataConfig.baseUrl)
        page.waitForLoadState(LoadState.NETWORKIDLE)
      }
      val ctx = new ControllerContext(page)
      // Defensive backfill so downstream builders (FormController.labelCandidates,
      // ExecutionController.runButton) can read per-operator _controllerHints
      // even if a script skips insertViaDrag.
      ctx.currentOperatorType = Some(scenario.operatorType)
      val steps =
        if (storageState.nonEmpty) scenario.steps.filterNot(_.name.startsWith("Prepare:"))
        else scenario.steps
      runSteps(ctx, if (steps.nonEmpty) steps else scenario.steps) match {
        case None         => ()
        case Some(stepEx) => failure = Some(stepEx)
      }

      if (failure.isEmpty) {
        page.waitForTimeout(TestDataConfig.uiConfig.resultPanelHoldMs.toDouble)
        println(s"  Completed: ${scenario.operatorName}")
      }
    } catch {
      case e: Exception =>
        println(s"  ERROR: ${e.getMessage}")
        failure = Some((e, "<unknown>"))
    }

    val capturedShot: Option[Path] = failure.flatMap { _ =>
      try {
        val shotPath = videoDir.resolve(s"${scenario.outputFileName.stripSuffix(".webm")}_failure.png")
        page.screenshot(new Page.ScreenshotOptions().setPath(shotPath))
        Some(shotPath)
      } catch { case _: Exception => None }
    }
    val capturedUrl = try page.url() catch { case _: Exception => "" }
    val workflowError = if (failure.nonEmpty) extractWorkflowErrorText(page) else None

    var videoPath: Path = null
    try {
      context.close()
      if (video != null) videoPath = video.path()
    } catch { case _: Exception => }

    val finalVideo: Option[Path] =
      if (videoPath != null) Some(renameVideoFile(videoPath, scenario.outputFileName)) else None

    failure match {
      case Some((ex, stepName)) =>
        val stHead = ex.getStackTrace.take(8).map(_.toString).mkString("\n")
        val rf = RunFailure(
          operatorName = scenario.operatorName,
          operatorType = scenario.operatorType,
          category = scenario.category,
          stepName = stepName,
          exceptionClass = ex.getClass.getName,
          exceptionMessage = Option(ex.getMessage).getOrElse(""),
          stackTraceHead = stHead,
          pageUrl = capturedUrl,
          consoleErrors = consoleErrors.toSeq.distinct.take(30),
          workflowErrorText = workflowError,
          screenshotPath = capturedShot
        )
        ScenarioResult.Failure(rf, finalVideo)
      case None =>
        finalVideo match {
          case Some(p) => ScenarioResult.Success(p)
          case None =>
            val rf = RunFailure(
              operatorName = scenario.operatorName,
              operatorType = scenario.operatorType,
              category = scenario.category,
              stepName = "Finish",
              exceptionClass = "NoVideoProduced",
              exceptionMessage = "Playwright returned no video file",
              stackTraceHead = "",
              pageUrl = capturedUrl,
              consoleErrors = consoleErrors.toSeq.distinct.take(30),
              workflowErrorText = workflowError,
              screenshotPath = None
            )
            ScenarioResult.Failure(rf, None)
        }
    }
  }

  private def runSteps(ctx: ControllerContext, steps: Seq[ControllerStep]): Option[(Throwable, String)] = {
    steps.foreach { step =>
      try {
        println(s"[${step.name}] Executing...")
        step.run(ctx)
        println(s"[${step.name}] Done")
      } catch {
        case e: Throwable => return Some((e, step.name))
      }
    }
    None
  }

  private def extractWorkflowErrorText(page: Page): Option[String] = {
    try {
      val locs = Seq(
        ".workflow-execution-error",
        ".ant-message-error",
        "[data-test='workflow-error']"
      )
      locs.flatMap { sel =>
        try {
          val l = page.locator(sel).first()
          if (l.count() > 0) Option(l.innerText()).map(_.trim).filter(_.nonEmpty) else None
        } catch { case _: Exception => None }
      }.headOption
    } catch { case _: Exception => None }
  }

  private def renameVideoFile(videoPath: Path, newName: String): Path = {
    try {
      val targetPath = videoPath.getParent.resolve(newName)
      Files.move(videoPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      println(s"Saved as: $newName")
      targetPath
    } catch {
      case e: Exception =>
        println(s"Warning: Could not rename video - ${e.getMessage}")
        videoPath
    }
  }

  private def loginOnce(browser: Browser): Option[String] = {
    val options = new Browser.NewContextOptions()
      .setViewportSize(
        TestDataConfig.uiConfig.recordWidth,
        TestDataConfig.uiConfig.recordHeight
      )
    val context = browser.newContext(options)
    val page = context.newPage()

    try {
      page.navigate(TestDataConfig.baseUrl)
      page.waitForLoadState(LoadState.NETWORKIDLE)
      page.waitForTimeout(400)

      val user = TestDataConfig.users.headOption
        .getOrElse(throw new IllegalStateException("TestDataConfig.users is empty"))
      val ctl = new LoginControllerBuilder(new ControllerContext(page))
      ctl.login(user.username, user.password).execute()

      Some(context.storageState())
    } catch {
      case e: Exception =>
        println(s"Warning: loginOnce failed, fallback to per-scenario prepare login. ${e.getMessage}")
        None
    } finally {
      try context.close()
      catch { case _: Exception => }
    }
  }
}
