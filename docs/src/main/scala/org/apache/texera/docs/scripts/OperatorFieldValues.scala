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

  // Meta-keys that describe the operator config itself, not actual form fields.
  // These are stripped before form-fill so the form controller doesn't try to
  // resolve a non-existent UI field with this name.
  private val metaKeys: Set[String] = Set("dataset")

  def typedValues(operatorType: String): Map[String, JsonNode] = {
    val node = operatorNode(operatorType)
    if (!node.isObject) return Map.empty

    val entries = node.fields().asScala
      .filter { entry => entry.getValue != null && !entry.getValue.isNull }
      .filterNot { entry => metaKeys.contains(entry.getKey) }
      .map(entry => entry.getKey -> entry.getValue)
      .toSeq
    ListMap(entries: _*)
  }

  // Returns "test1" / "test2" / ... — defaults to "test1" if not specified.
  def datasetKey(operatorType: String): String = {
    val node = operatorNode(operatorType)
    val ds = node.path("dataset").asText("")
    if (ds.nonEmpty) ds else "test1"
  }

  // Look up a single string value at the operator-field-values.json level for a
  // specific operator. Returns None if the key is missing/null/non-textual.
  // Used by Data Input scripts to pick which file to select from the dataset modal.
  def fieldValue(operatorType: String, key: String): Option[String] = {
    val node = operatorNode(operatorType).path(key)
    if (node.isMissingNode || node.isNull) None
    else {
      val text = node.asText("")
      if (text.isEmpty) None else Some(text)
    }
  }
}
