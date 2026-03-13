package org.apache.texera.docs.scripts

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

object OperatorFieldValues {
  private val mapper = new ObjectMapper()

  private val configFile: Path = Paths.get(
    "docs", "src", "main", "scala", "org", "apache", "texera", "docs", "config", "operator-field-values.json"
  )

  private lazy val loadedRoot: JsonNode = {
    if (!Files.exists(configFile)) {
      throw new IllegalStateException(
        s"operator-field-values.json not found: ${configFile.toString}"
      )
    }
    mapper.readTree(configFile.toFile)
  }

  private def operatorNode(operatorType: String): JsonNode =
    loadedRoot.path("operators").path(operatorType)

  def typedValues(operatorType: String): Map[String, JsonNode] = {
    val node = operatorNode(operatorType)
    if (!node.isObject) return Map.empty

    val entries = node.fields().asScala
      .filter { entry => entry.getValue != null && !entry.getValue.isNull }
      .map(entry => entry.getKey -> entry.getValue)
      .toSeq
    ListMap(entries: _*)
  }
}
