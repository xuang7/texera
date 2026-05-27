package org.apache.texera.docs.autofix

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator
import org.apache.texera.docs.scripts.OperatorFieldValues

import java.nio.file.Files

object FailureCollector {

  private val mapper = new ObjectMapper()
  private val configPath = OperatorFieldValues.configFile

  def enrich(rf: RunFailure): FailureContext = {
    val root: JsonNode =
      if (Files.exists(configPath)) mapper.readTree(configPath.toFile)
      else mapper.createObjectNode()

    val opConfig = root.path("operators").path(rf.operatorType) match {
      case n if n.isMissingNode || n.isNull => mapper.createObjectNode()
      case n                                 => n
    }

    val datasetKey = opConfig.path("dataset").asText("") match {
      case s if s.nonEmpty => s
      case _               => OperatorFieldValues.DefaultDatasetKey
    }

    val datasetSchema = root.path("datasets").path(datasetKey).path("schema") match {
      case n if n.isMissingNode || n.isNull => mapper.createArrayNode()
      case n                                 => n
    }

    val operatorSchema = OperatorMetadataGenerator.allOperatorMetadata.operators
      .find(_.operatorType == rf.operatorType)
      .map(_.jsonSchema)
      .getOrElse(mapper.createObjectNode().asInstanceOf[JsonNode])

    FailureContext(
      runFailure = rf,
      currentConfig = opConfig,
      operatorSchema = operatorSchema,
      datasetKey = datasetKey,
      datasetSchema = datasetSchema
    )
  }
}
