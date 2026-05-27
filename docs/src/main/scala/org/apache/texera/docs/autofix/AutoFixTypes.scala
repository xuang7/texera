package org.apache.texera.docs.autofix

import com.fasterxml.jackson.databind.JsonNode

import java.nio.file.Path

case class ValidationIssue(operatorType: String, key: String, message: String) {
  override def toString: String =
    if (key.isEmpty) s"$operatorType: $message"
    else s"$operatorType.$key: $message"
}

case class RunFailure(
    operatorName: String,
    operatorType: String,
    category: String,
    stepName: String,
    exceptionClass: String,
    exceptionMessage: String,
    stackTraceHead: String,
    pageUrl: String,
    consoleErrors: Seq[String],
    workflowErrorText: Option[String],
    screenshotPath: Option[Path]
)

case class FailureContext(
    runFailure: RunFailure,
    currentConfig: JsonNode,
    operatorSchema: JsonNode,
    datasetKey: String,
    datasetSchema: JsonNode
) {
  def operatorName: String = runFailure.operatorName
  def operatorType: String = runFailure.operatorType
}

case class PatchOp(op: String, path: String, value: Option[JsonNode])

case class PatchEnvelope(
    operatorType: String,
    reasoning: String,
    confidence: String,
    ops: Seq[PatchOp]
)

sealed trait RunResult
object RunResult {
  case class Success(videoPath: Path) extends RunResult
  case class Failure(context: FailureContext) extends RunResult
}

case class AttemptRecord(
    attempt: Int,
    failure: FailureContext,
    proposedPatch: Option[PatchEnvelope],
    applied: Boolean
)

case class AutoFixOutcome(
    operatorName: String,
    succeeded: Boolean,
    attempts: Seq[AttemptRecord],
    finalVideo: Option[Path],
    elapsedMs: Long = 0L
)

case class TokenUsage(input: Int, output: Int, cacheRead: Int = 0, cacheCreation: Int = 0) {
  def +(other: TokenUsage): TokenUsage =
    TokenUsage(
      input = input + other.input,
      output = output + other.output,
      cacheRead = cacheRead + other.cacheRead,
      cacheCreation = cacheCreation + other.cacheCreation
    )
}
object TokenUsage {
  val Zero: TokenUsage = TokenUsage(0, 0)
}

/** Run-level counters aggregated by AutoFixOrchestrator. */
case class AutoFixRunStats(
    llmCallsTotal: Int,
    llmFailuresTotal: Int,
    elapsedMsTotal: Long,
    tokenUsageTotal: TokenUsage
)
