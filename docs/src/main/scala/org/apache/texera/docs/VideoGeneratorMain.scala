package org.apache.texera.docs

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

  // Operators that crash or time-out at runtime regardless of args. Listed by
  // user-friendly name (matches `OperatorScript.operatorName`).
  private val runtimeSkip: Set[String] = Set(
    // GaussianNB rejects sparse matrices from CountVectorizer/TfidfTransformer.
    "Gaussian Naive Bayes",
    "Training: Gaussian Naive Bayes",
    // LinearRegression default has no countVectorizer; PolynomialFeatures fails on text columns.
    "Linear Regression",
    // GradientBoosting on high-dim sparse TF-IDF is too slow and trips Playwright timeouts.
    "Gradient Boosting",
    "Training: Gradient Boosting"
  ).map(normalize)

  def main(args: Array[String]): Unit = {
    println("\n╔════════════════════════════════════════════════════╗")
    println("║  Texera Operator Demo Video Generator             ║")
    println("╚════════════════════════════════════════════════════╝")

    val groupOpt = parseFirst(args, "--group=")
    val names = parseNames(args)
    val limitOpt = parseLimit(args)

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

    new VideoRunner().generateVideos(selected.map(toScenario))
    println("Complete.\n")
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
      category = script.category,
      steps = Seq(
        ControllerStep(s"Prepare: ${script.operatorName}")(script.prepare),
        ControllerStep(s"Execute: ${script.operatorName}")(script.execute),
        ControllerStep(s"Finish: ${script.operatorName}")(script.finish)
      ),
      outputFileName = script.outputFileName
    )
}
