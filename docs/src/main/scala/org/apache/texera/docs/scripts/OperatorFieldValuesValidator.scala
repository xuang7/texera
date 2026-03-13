package org.apache.texera.docs.scripts

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator

import java.nio.file.Paths
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

  def main(args: Array[String]): Unit = {
    val root = mapper.readTree(configPath.toFile)
    val operatorsNode = root.path("operators")
    val datasetSchemaNode = root.path("dataset").path("schema")

    val datasetTypes: Map[String, String] =
      if (!datasetSchemaNode.isArray) Map.empty
      else datasetSchemaNode.elements().asScala.flatMap { n =>
        val name = n.path("name").asText("")
        val tpe = n.path("type").asText("").toLowerCase
        if (name.nonEmpty && tpe.nonEmpty) Some(name -> tpe) else None
      }.toMap

    val metadataByType = OperatorMetadataGenerator.allOperatorMetadata.operators.map(m => m.operatorType -> m).toMap

    var issueCount = 0
    def issue(msg: String): Unit = {
      issueCount += 1
      println(s"[ISSUE] $msg")
    }

    operatorsNode.fields().asScala.foreach { opEntry =>
      val opType = opEntry.getKey
      val opConfig = opEntry.getValue
      val meta = metadataByType.get(opType)
      if (meta.isEmpty) {
        issue(s"$opType: operator type not found in metadata")
      } else if (!opConfig.isObject) {
        issue(s"$opType: config is not an object")
      } else {
        val schema = meta.get.jsonSchema
        val propertiesNode = schema.path("properties")
        val requiredSet = schema.path("required").elements().asScala.map(_.asText("")).filter(_.nonEmpty).toSet
        val rulesNode = schema.path("attributeTypeRules")

        val keyAliasToProperty: Map[String, String] = {
          val pairs = propertiesNode.fields().asScala.flatMap { p =>
            val propKey = p.getKey
            val title = p.getValue.path("title").asText("")
            val base = Seq(normalize(propKey), normalize(title)).filter(_.nonEmpty).map(_ -> propKey)
            base
          }
          pairs.toMap
        }

        val seenProps = scala.collection.mutable.Set.empty[String]

        opConfig.fields().asScala.foreach { f =>
          val configKey = f.getKey
          val valueNode = f.getValue
          val resolvedProp = keyAliasToProperty.get(normalize(configKey))
          if (resolvedProp.isEmpty) {
            issue(s"$opType.$configKey: key not found in operator schema")
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
                  issue(s"$opType.$configKey: column '$column' type=$actualType violates allowed types=${allowedTypes.mkString("[", ", ", "]")}")
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
          issue(s"$opType: missing required property '$missing' in config")
        }

        if (opType == "PieChart") {
          val nameCol = Option(opConfig.path("name")).map(_.asText("")).getOrElse("")
          if (nameCol == "genre" || nameCol == "rating" || nameCol == "country" || nameCol == "company") {
            issue(
              s"$opType.name='$nameCol': PieChart source code requires unique names; duplicate labels will error unless pre-aggregated"
            )
          }
        }
      }
    }

    if (issueCount == 0) println("[OK] No schema/type issues found.")
    else println(s"[SUMMARY] $issueCount issue(s) found.")
  }
}
