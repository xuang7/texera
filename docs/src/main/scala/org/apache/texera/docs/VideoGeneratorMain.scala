// VideoGeneratorMain.scala
package org.apache.texera.docs

import org.apache.texera.docs.orchestrator.VideoRunner
import org.apache.texera.docs.scripts.VideoScenarioScripts

object VideoGeneratorMain {
  def main(args: Array[String]): Unit = {
    println("\n╔════════════════════════════════════════════════════╗")
    println("║  Texera Operator Demo Video Generator             ║")
    println("╚════════════════════════════════════════════════════╝")

    val orchestrator = new VideoRunner()
    val scenarios = VideoScenarioScripts.allScenarios
    val limit = parseLimit(args).getOrElse(scenarios.length) // test use
    orchestrator.generateVideos(scenarios.take(limit))

    println("Complete.\n")
  }

  private def parseLimit(args: Array[String]): Option[Int] = {
    args.toList.flatMap { arg =>
      if (arg.startsWith("--limit=")) {
        arg.stripPrefix("--limit=").toIntOption
      } else {
        arg.toIntOption
      }
    }.headOption
  }
}
