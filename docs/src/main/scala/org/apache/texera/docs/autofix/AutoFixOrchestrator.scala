package org.apache.texera.docs.autofix

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator
import org.apache.texera.docs.orchestrator.{OperatorScenario, ScenarioResult, VideoRunner}
import org.apache.texera.docs.scripts.{OperatorFieldValues, OperatorFieldValuesValidator}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

class AutoFixOrchestrator(maxAttempts: Int = 3) {

  // Number of retries per LLM call for transient errors (network/5xx). A missing
  // API key fails fast without retrying.
  private val llmRetries: Int = 2

  private val mapper = new ObjectMapper()
  private val unfixedPath: Path = Paths.get("docs", "generated", "unfixed.json")

  // Run-level counters; exposed via `stats` after `run`.
  private var llmCallsTotal = 0
  private var llmFailuresTotal = 0
  private var elapsedMsTotal = 0L
  private var tokenUsageTotal = TokenUsage.Zero

  def stats: AutoFixRunStats =
    AutoFixRunStats(llmCallsTotal, llmFailuresTotal, elapsedMsTotal, tokenUsageTotal)

  def run(scenarios: Seq[OperatorScenario]): Seq[AutoFixOutcome] = {
    if (sys.env.getOrElse("ANTHROPIC_API_KEY", "").trim.isEmpty) {
      println("[AutoFix] ANTHROPIC_API_KEY is not set; auto-fix cannot propose patches.")
      println("[AutoFix] Set the env var and re-run, or drop --auto-fix to record without LLM.")
      return Seq.empty
    }

    val runStart = System.currentTimeMillis()
    val runner = new VideoRunner()
    runner.start()
    try {
      scenarios.map(s => runOne(runner, s))
    } finally {
      runner.close()
      elapsedMsTotal = System.currentTimeMillis() - runStart
      println("\n[AutoFix] Done. See docs/generated/unfixed.json for unresolved operators.\n")
    }
  }

  private def runOne(runner: VideoRunner, scenario: OperatorScenario): AutoFixOutcome = {
    val opType = scenario.operatorType
    val scenarioStart = System.currentTimeMillis()
    println(s"\n[AutoFix] ===== ${scenario.operatorName} ($opType) =====")

    val snapshot = PatchApplier.snapshot(opType)
    val attempts = scala.collection.mutable.ArrayBuffer.empty[AttemptRecord]

    var done = false
    var attemptNo = 1
    var outcome: Option[AutoFixOutcome] = None
    while (!done && attemptNo <= maxAttempts) {
      println(s"[AutoFix] attempt $attemptNo/$maxAttempts")
      runner.runScenario(scenario) match {
        case ScenarioResult.Success(p) =>
          val elapsed = System.currentTimeMillis() - scenarioStart
          println(s"[AutoFix] ✓ ${scenario.operatorName} succeeded on attempt $attemptNo (${elapsed}ms)")
          outcome = Some(AutoFixOutcome(scenario.operatorName, succeeded = true, attempts.toSeq, Some(p), elapsed))
          done = true

        case ScenarioResult.Failure(rf, _) =>
          println(s"[AutoFix] ✗ failure: ${rf.exceptionClass}: ${rf.exceptionMessage.take(200)}")
          val fc = FailureCollector.enrich(rf)
          val (patchOpt, applied) = attemptPatch(fc)
          attempts += AttemptRecord(attemptNo, fc, patchOpt, applied)
          if (!applied) {
            println(s"[AutoFix] no applicable patch this round; aborting further attempts")
            done = true
          } else {
            attemptNo += 1
          }
      }
    }

    outcome.getOrElse {
      // All attempts exhausted (or aborted). Restore original config and record failure.
      PatchApplier.restore(opType, snapshot)
      recordUnfixed(scenario.operatorName, attempts.toSeq)
      val elapsed = System.currentTimeMillis() - scenarioStart
      println(s"[AutoFix] ✗ ${scenario.operatorName} unresolved; original config restored (${elapsed}ms)")
      AutoFixOutcome(scenario.operatorName, succeeded = false, attempts.toSeq, None, elapsed)
    }
  }

  /**
   * Wraps LLMPatchProposer with transient-error retries. Increments run-level
   * `llmCallsTotal` / `llmFailuresTotal`. A missing API key is permanent and
   * fails fast without retrying.
   */
  private def proposePatchWithRetry(
    fc: FailureContext,
    priorError: Option[String]
  ): Either[String, PatchEnvelope] = {
    var lastErr = "no LLM call attempted"
    var i = 1
    while (i <= llmRetries) {
      llmCallsTotal += 1
      LLMPatchProposer.proposePatch(fc, priorError) match {
        case Right((env, usage)) =>
          tokenUsageTotal = tokenUsageTotal + usage
          return Right(env)
        case Left(err) if err.contains("API_KEY") =>
          return Left(err) // permanent
        case Left(err) =>
          llmFailuresTotal += 1
          lastErr = err
          if (i < llmRetries) {
            println(s"[AutoFix] LLM transient error (try $i/$llmRetries): $err — retrying")
          }
          i += 1
      }
    }
    Left(s"LLM failed after $llmRetries tries: $lastErr")
  }

  /**
   * Ask LLM for a patch, validate; if validator rejects, give it one chance to self-correct.
   * Returns (proposedPatch, applied).
   */
  private def attemptPatch(fc: FailureContext): (Option[PatchEnvelope], Boolean) = {
    val schemas = schemasByDatasetFromRoot()
    val metadata = OperatorMetadataGenerator.allOperatorMetadata.operators
      .map(m => m.operatorType -> m).toMap
    val baselineIssues = OperatorFieldValuesValidator
      .validateOperator(fc.operatorType, fc.currentConfig, schemas, metadata)
      .toSet

    def newlyIntroducedIssues(allIssues: Seq[ValidationIssue]): Seq[ValidationIssue] =
      allIssues.filterNot(baselineIssues.contains)

    def tryOnce(priorError: Option[String]): Either[String, (PatchEnvelope, Seq[ValidationIssue])] = {
      proposePatchWithRetry(fc, priorError) match {
        case Left(err) => Left(s"LLM error: $err")
        case Right(env) =>
          if (env.ops.isEmpty) return Left("LLM returned empty diff")
          PatchApplier.dryRun(env) match {
            case Left(applyErr) => Left(s"patch dry-run failed: $applyErr")
            case Right(patched) =>
              val issues = OperatorFieldValuesValidator.validateOperator(env.operatorType, patched, schemas, metadata)
              Right((env, newlyIntroducedIssues(issues)))
          }
      }
    }

    tryOnce(None) match {
      case Left(err) =>
        println(s"[AutoFix] LLM proposal failed: $err")
        (None, false)
      case Right((env, issues)) if issues.isEmpty =>
        println(s"[AutoFix] applying patch: ${env.reasoning} (${env.ops.size} ops)")
        env.ops.foreach(op => println(s"[AutoFix]   ${op.op} ${op.path} = ${op.value.map(_.toString).getOrElse("")}"))
        PatchApplier.apply(env) match {
          case Right(_)  => (Some(env), true)
          case Left(err) =>
            println(s"[AutoFix] failed to apply patch: $err")
            (Some(env), false)
        }
      case Right((env, issues)) =>
        val errMsg = issues.map(_.toString).mkString("; ")
        println(s"[AutoFix] validator rejected patch: $errMsg — asking LLM to self-correct")
        tryOnce(Some(errMsg)) match {
          case Left(err) =>
            println(s"[AutoFix] self-correction failed: $err")
            (Some(env), false)
          case Right((env2, issues2)) if issues2.isEmpty =>
            println(s"[AutoFix] applying corrected patch: ${env2.reasoning}")
            env2.ops.foreach(op => println(s"[AutoFix]   ${op.op} ${op.path} = ${op.value.map(_.toString).getOrElse("")}"))
            PatchApplier.apply(env2) match {
              case Right(_)  => (Some(env2), true)
              case Left(err) =>
                println(s"[AutoFix] failed to apply corrected patch: $err")
                (Some(env2), false)
            }
          case Right((env2, issues2)) =>
            println(s"[AutoFix] corrected patch still has issues: ${issues2.map(_.toString).mkString("; ")}")
            (Some(env2), false)
        }
    }
  }

  private def schemasByDatasetFromRoot(): Map[String, Map[String, String]] = {
    val cfgPath = OperatorFieldValues.configFile
    if (!Files.exists(cfgPath)) return Map.empty
    val root = mapper.readTree(cfgPath.toFile)
    val ds = root.path("datasets")
    if (!ds.isObject) return Map.empty
    import scala.jdk.CollectionConverters._
    ds.fields().asScala.map { e =>
      val key = e.getKey
      val arr = e.getValue.path("schema")
      val cols =
        if (!arr.isArray) Map.empty[String, String]
        else arr.elements().asScala.flatMap { n =>
          val nm = n.path("name").asText("")
          val tp = n.path("type").asText("").toLowerCase
          if (nm.nonEmpty && tp.nonEmpty) Some(nm -> tp) else None
        }.toMap
      key -> cols
    }.toMap
  }

  private def recordUnfixed(operatorName: String, attempts: Seq[AttemptRecord]): Unit = {
    Files.createDirectories(unfixedPath.getParent)
    val arr: ArrayNode =
      if (Files.exists(unfixedPath)) {
        try mapper.readTree(unfixedPath.toFile) match {
          case a: ArrayNode => a
          case _            => mapper.createArrayNode()
        } catch { case _: Exception => mapper.createArrayNode() }
      } else mapper.createArrayNode()

    val entry = mapper.createObjectNode()
    entry.put("operator", operatorName)
    entry.put("timestamp", java.time.Instant.now().toString)
    val attemptsNode = mapper.createArrayNode()
    attempts.foreach { a =>
      val n = mapper.createObjectNode()
      n.put("attempt", a.attempt)
      n.put("exception", s"${a.failure.runFailure.exceptionClass}: ${a.failure.runFailure.exceptionMessage}")
      a.failure.runFailure.workflowErrorText.foreach(t => n.put("workflowError", t))
      a.proposedPatch.foreach { p =>
        val patchNode = mapper.createObjectNode()
        patchNode.put("reasoning", p.reasoning)
        patchNode.put("confidence", p.confidence)
        val opsNode = mapper.createArrayNode()
        p.ops.foreach { op =>
          val o = mapper.createObjectNode()
          o.put("op", op.op)
          o.put("path", op.path)
          op.value.foreach(v => o.set[com.fasterxml.jackson.databind.node.ObjectNode]("value", v))
          opsNode.add(o)
        }
        patchNode.set[com.fasterxml.jackson.databind.node.ObjectNode]("ops", opsNode)
        n.set[com.fasterxml.jackson.databind.node.ObjectNode]("proposedPatch", patchNode)
      }
      n.put("applied", a.applied)
      attemptsNode.add(n)
    }
    entry.set[com.fasterxml.jackson.databind.node.ObjectNode]("attempts", attemptsNode)
    arr.add(entry)
    Files.write(
      unfixedPath,
      mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr).getBytes(StandardCharsets.UTF_8)
    )
  }
}
