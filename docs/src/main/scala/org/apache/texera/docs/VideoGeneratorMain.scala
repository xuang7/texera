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
    orchestrator.generateVideos(VideoScenarioScripts.allScenarios)

    println("Complete.\n")
  }
}
