package org.apache.texera.docs

import org.apache.texera.docs.autofix.AutoFixOrchestrator
import org.apache.texera.docs.controllers.ControllerStep
import org.apache.texera.docs.orchestrator.{OperatorScenario, VideoRunner}
import org.apache.texera.docs.scripts.OperatorScript
import org.apache.texera.docs.scripts.operators.OperatorScriptRegistry

/**
 * Single entry point for generating operator demo videos.
 *
 * Usage:
 *   sbt 'Docs/runMain org.apache.texera.docs.VideoGeneratorMain'
 *   sbt 'Docs/runMain org.apache.texera.docs.VideoGeneratorMain --group=Sklearn'
 *   sbt 'Docs/runMain org.apache.texera.docs.VideoGeneratorMain --group=Sklearn --names=AdaptiveBoosting'
 *   sbt 'Docs/runMain org.apache.texera.docs.VideoGeneratorMain --names=BarChart,PieChart --limit=2'
 *
 * Args:
 *   --group=<name>       restrict to a single group (matches `script.category`,
 *                        normalized to ignore case + non-alphanumerics).
 *                        Common values: Sklearn, "Sklearn Training", "Advanced Sklearn",
 *                        "Data Cleaning", "Data Input", "Visualization Basic", ...
 *   --names=A,B,C        restrict to specific operator user-friendly names
 *                        (also normalized).
 *   --limit=N            cap the number of scenarios actually run.
 *
 * Operators known to fail at runtime on the current movies.csv / movie_tree.csv
 * datasets are listed in `runtimeSkip` and excluded from every run.
 */
object VideoGeneratorMain {

  // Operators that cannot be demoed under the current sample workflows. They
  // are always skipped so the pipeline doesn't waste LLM tokens or wall time
  // trying to "fix" them. Listed by user-friendly name (matches
  // `OperatorScript.operatorName`); comparison is normalized below.
  private val runtimeSkip: Set[String] = Set(
    // database connectors — need real DB credentials
    "MySQL Source",
    "PostgreSQL Source",
    "AsterixDB Source",
    // external APIs — need real API keys
    "Reddit Search",
    "Twitter Search API",
    "Twitter Full Archive Search API",
    // ML scoring / prediction ops — need a "prediction" or "model" column from
    // an upstream training operator. sample_ML.json has no training operator,
    // so these always fail at runtime. Re-enable once we ship a training
    // template (README TODO #1).
    "Machine Learning Scorer",
    "Sklearn Prediction",
    "Sklearn Testing",
    // placeholder operator with no execution semantics
    "Dummy"
  ).map(_.toLowerCase.replaceAll("[^a-z0-9]", ""))

  def main(args: Array[String]): Unit = {
    println("\n╔════════════════════════════════════════════════════╗")
    println("║  Texera Operator Demo Video Generator             ║")
    println("╚════════════════════════════════════════════════════╝")

    val groupOpt = parseFirst(args, "--group=")
    val names = parseNames(args)
    val limitOpt = parseLimit(args)
    val autoFix = args.exists(a => a == "--auto-fix" || a == "--autofix")
    val maxAttempts = parseFirst(args, "--max-attempts=").flatMap(_.toIntOption).getOrElse(3)

    val pool: Seq[OperatorScript] = groupOpt match {
      case Some(g) =>
        val target = normalize(g)
        OperatorScriptRegistry.all.filter(s => normalize(s.category) == target)
      case None => OperatorScriptRegistry.all
    }

    val byName: Seq[OperatorScript] = if (names.nonEmpty) {
      val want = names.map(normalize).toSet
      pool.filter(s => want.contains(normalize(s.operatorName)))
    } else pool

    val runnable = byName.filterNot(s => runtimeSkip.contains(normalize(s.operatorName)))
    val skipped = byName.length - runnable.length
    val selected = limitOpt.map(runnable.take).getOrElse(runnable)

    val groupLabel = groupOpt.map(g => s" group='$g'").getOrElse("")
    println(s"[VideoGenerator]$groupLabel selected=${selected.length}" +
      (if (skipped > 0) s" (skipped $skipped runtime-incompatible)" else ""))

    if (selected.isEmpty) {
      println("Nothing to run; check --group / --names spelling.")
      return
    }

    val scenarios = selected.map(toScenario)
    if (autoFix) {
      println(s"[VideoGenerator] auto-fix enabled, max-attempts=$maxAttempts")
      val orchestrator = new AutoFixOrchestrator(maxAttempts)
      val outcomes = orchestrator.run(scenarios)
      printAutoFixReport(outcomes, orchestrator.stats, groupOpt, maxAttempts)
    } else {
      new VideoRunner().generateVideos(scenarios)
    }
    println("Complete.\n")
  }

  // Group-level summary: succeed/fail counts, list of failures with brief reason,
  // a breakdown of successes by "attempt 1 vs. needed LLM retry" (tells us
  // whether the agent is doing useful work), failure-class buckets, and run-wide
  // LLM call / wall-time stats.
  private def printAutoFixReport(
    outcomes: Seq[org.apache.texera.docs.autofix.AutoFixOutcome],
    stats: org.apache.texera.docs.autofix.AutoFixRunStats,
    groupOpt: Option[String],
    maxAttempts: Int
  ): Unit = {
    val total = outcomes.length
    val succeeded = outcomes.filter(_.succeeded)
    val failed = outcomes.filterNot(_.succeeded)
    val firstTry = succeeded.filter(_.attempts.isEmpty)
    val retryWin = succeeded.filterNot(_.attempts.isEmpty)

    val bar = "═" * 70
    val line = "─" * 70

    println()
    println(bar)
    println("  AutoFix Run Report")
    println(bar)
    groupOpt.foreach(g => println(s"  group:   $g"))
    println(s"  total:   $total")
    println(s"  ✓ succeeded: ${succeeded.length}    ✗ failed: ${failed.length}")

    if (failed.nonEmpty) {
      println(line)
      println("  ✗ Failed:")
      failed.foreach { o =>
        val attemptsUsed = math.max(o.attempts.length, 1)
        val lastEx = o.attempts.lastOption.map { a =>
          val rf = a.failure.runFailure
          val firstLine = Option(rf.exceptionMessage).getOrElse("").split("\n", 2).head
          val msg = if (firstLine.length > 80) firstLine.take(77) + "..." else firstLine
          s"${rf.exceptionClass.split('.').last}: $msg"
        }.getOrElse("<no failure details captured>")
        println(f"    - ${o.operatorName}%-40s ($attemptsUsed/${maxAttempts} attempts)  $lastEx")
      }
    }

    if (firstTry.nonEmpty) {
      println(line)
      println(s"  ✓ Succeeded on attempt 1: ${firstTry.length}")
    }

    if (retryWin.nonEmpty) {
      println(line)
      println("  ✓ Succeeded after LLM patch:")
      retryWin.foreach { o =>
        val patches = o.attempts.count(_.applied)
        val patchedSummary = if (patches == 1) "1 patch" else s"$patches patches"
        println(f"    - ${o.operatorName}%-40s (${o.attempts.length + 1} attempts, $patchedSummary)")
      }
    }

    // Failure buckets — group by exception-class root, so a recurring class tells
    // us which prompt rule isn't landing.
    if (failed.nonEmpty) {
      println(line)
      println("  Failure buckets:")
      failed
        .groupBy { o =>
          o.attempts.lastOption
            .map(_.failure.runFailure.exceptionClass.split('.').last)
            .getOrElse("UnknownError")
        }
        .toSeq
        .sortBy(-_._2.size)
        .foreach { case (cls, ops) => println(f"    $cls%-30s ${ops.size}%3d") }
    }

    // Run-wide LLM + wall-clock stats from the orchestrator.
    println(line)
    val mins = stats.elapsedMsTotal / 60000
    val secs = (stats.elapsedMsTotal / 1000) % 60
    val tok = stats.tokenUsageTotal
    println(f"  LLM calls: ${stats.llmCallsTotal}%4d   transient failures: ${stats.llmFailuresTotal}%2d")
    println(f"  Tokens:    ${tok.input}%,d in / ${tok.output}%,d out")
    if (tok.cacheRead > 0 || tok.cacheCreation > 0) {
      println(f"             cache: ${tok.cacheRead}%,d read / ${tok.cacheCreation}%,d created")
    }
    println(f"  Wall time: ${mins}m${secs}%02ds")

    println(line)
    println("  Details: docs/generated/unfixed.json")
    println(bar)
    println()
  }

  private def normalize(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]", "")

  private def parseFirst(args: Array[String], prefix: String): Option[String] =
    args.collectFirst { case a if a.startsWith(prefix) => a.stripPrefix(prefix).trim }
      .filter(_.nonEmpty)

  private def parseLimit(args: Array[String]): Option[Int] =
    args.toList.flatMap { a =>
      if (a.startsWith("--limit=")) a.stripPrefix("--limit=").toIntOption
      else a.toIntOption
    }.headOption

  private def parseNames(args: Array[String]): Seq[String] =
    args.toList.flatMap { a =>
      if (a.startsWith("--names=")) a.stripPrefix("--names=").split(",").toSeq.map(_.trim).filter(_.nonEmpty)
      else Nil
    }

  private def toScenario(script: OperatorScript): OperatorScenario =
    OperatorScenario(
      operatorName = script.operatorName,
      operatorType = script.operatorType,
      category = script.category,
      steps = Seq(
        ControllerStep(s"Prepare: ${script.operatorName}")(script.prepare),
        ControllerStep(s"Execute: ${script.operatorName}")(script.execute),
        ControllerStep(s"Finish: ${script.operatorName}")(script.finish)
      ),
      outputFileName = script.outputFileName
    )
}
