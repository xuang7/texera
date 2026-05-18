package org.apache.texera.docs.scripts

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator
import org.apache.texera.amber.operator.metadata.OperatorMetadata
import org.apache.texera.docs.autofix.ValidationIssue

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

object OperatorFieldValuesValidator {

  private val mapper = new ObjectMapper()
  private val configPath = Paths.get(
    "docs",
    "src",
    "main",
    "scala",
    "org",
    "apache",
    "texera",
    "docs",
    "config",
    "operator-field-values.json"
  )

  private def normalize(s: String): String =
    s.replaceAll("[^A-Za-z0-9]", "").toLowerCase

  private val metaKeys: Set[String] = Set("dataset", "_controllerHints")
  private val defaultDataset = "test1"

  private def loadRoot(): JsonNode = mapper.readTree(configPath.toFile)

  private def schemasByDataset(root: JsonNode): Map[String, Map[String, String]] = {
    val datasetsNode = root.path("datasets")
    if (!datasetsNode.isObject) Map.empty
    else datasetsNode.fields().asScala.map { e =>
      val key = e.getKey
      val schemaArr = e.getValue.path("schema")
      val cols =
        if (!schemaArr.isArray) Map.empty[String, String]
        else schemaArr.elements().asScala.flatMap { n =>
          val name = n.path("name").asText("")
          val tpe = n.path("type").asText("").toLowerCase
          if (name.nonEmpty && tpe.nonEmpty) Some(name -> tpe) else None
        }.toMap
      key -> cols
    }.toMap
  }

  private def metadataByType: Map[String, OperatorMetadata] =
    OperatorMetadataGenerator.allOperatorMetadata.operators.map(m => m.operatorType -> m).toMap

  def validateOperator(
      operatorType: String,
      opConfig: JsonNode,
      schemas: Map[String, Map[String, String]],
      metadata: Map[String, OperatorMetadata]
  ): Seq[ValidationIssue] = {
    val issues = scala.collection.mutable.ArrayBuffer.empty[ValidationIssue]
    def issue(key: String, msg: String): Unit =
      issues += ValidationIssue(operatorType, key, msg)

    val meta = metadata.get(operatorType)
    if (meta.isEmpty) {
      issue("", "operator type not found in metadata")
      return issues.toSeq
    }
    if (!opConfig.isObject) {
      issue("", "config is not an object")
      return issues.toSeq
    }

    val datasetKey = opConfig.path("dataset").asText("")
    val effectiveDataset = if (datasetKey.nonEmpty) datasetKey else defaultDataset
    val datasetTypes = schemas.getOrElse(effectiveDataset, Map.empty[String, String])
    if (!schemas.contains(effectiveDataset) && datasetKey.nonEmpty) {
      issue("", s"dataset='$datasetKey' has no schema in datasets section")
    }

    val schema = meta.get.jsonSchema
    val propertiesNode = schema.path("properties")
    val requiredSet = schema.path("required").elements().asScala.map(_.asText("")).filter(_.nonEmpty).toSet
    val rulesNode = schema.path("attributeTypeRules")

    val keyAliasToProperty: Map[String, String] = {
      val pairs = propertiesNode.fields().asScala.flatMap { p =>
        val propKey = p.getKey
        val title = p.getValue.path("title").asText("")
        Seq(normalize(propKey), normalize(title)).filter(_.nonEmpty).map(_ -> propKey)
      }
      pairs.toMap
    }

    val seenProps = scala.collection.mutable.Set.empty[String]

    opConfig.fields().asScala.filterNot(f => metaKeys.contains(f.getKey)).foreach { f =>
      val configKey = f.getKey
      val valueNode = f.getValue
      val resolvedProp = keyAliasToProperty.get(normalize(configKey))
      if (resolvedProp.isEmpty) {
        issue(configKey, "key not found in operator schema")
      } else {
        val prop = resolvedProp.get
        seenProps += prop
        val rule = rulesNode.path(prop)
        val allowedTypes: Set[String] =
          if (rule.isMissingNode || !rule.has("enum")) Set.empty
          else rule.path("enum").elements().asScala.map(_.asText("").toLowerCase).filter(_.nonEmpty).toSet

        def checkColumn(column: String): Unit = {
          if (column.isEmpty) return
          datasetTypes.get(column) match {
            case Some(actualType) if allowedTypes.nonEmpty && !allowedTypes.contains(actualType) =>
              issue(
                configKey,
                s"column '$column' type=$actualType violates allowed types=${allowedTypes.mkString("[", ", ", "]")}"
              )
            case None if allowedTypes.nonEmpty =>
              issue(configKey, s"column '$column' not found in dataset '$effectiveDataset'")
            case _ =>
          }
        }

        if (allowedTypes.nonEmpty) {
          if (valueNode.isTextual) checkColumn(valueNode.asText(""))
          else if (valueNode.isArray) {
            valueNode.elements().asScala.filter(_.isTextual).foreach(v => checkColumn(v.asText("")))
          }
        }
      }
    }

    requiredSet.diff(seenProps.toSet).foreach { missing =>
      issue("", s"missing required property '$missing' in config")
    }

    if (operatorType == "PieChart") {
      val nameCol = opConfig.path("name").asText("")
      if (Set("genre", "rating", "country", "company").contains(nameCol)) {
        issue(
          "name",
          s"PieChart requires unique names; '$nameCol' has duplicates without pre-aggregation"
        )
      }
    }

    issues.toSeq
  }

  def validateAll(): Seq[ValidationIssue] = {
    if (!Files.exists(configPath)) return Seq.empty
    val root = loadRoot()
    val schemas = schemasByDataset(root)
    val metadata = metadataByType
    val operatorsNode = root.path("operators")
    if (!operatorsNode.isObject) return Seq.empty

    operatorsNode.fields().asScala.flatMap { e =>
      validateOperator(e.getKey, e.getValue, schemas, metadata)
    }.toSeq
  }

  def main(args: Array[String]): Unit = {
    val issues = validateAll()
    issues.foreach(i => println(s"[ISSUE] $i"))
    if (issues.isEmpty) println("[OK] No schema/type issues found.")
    else println(s"[SUMMARY] ${issues.size} issue(s) found.")
  }
}
