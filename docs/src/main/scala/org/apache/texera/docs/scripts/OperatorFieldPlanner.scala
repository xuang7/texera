package org.apache.texera.docs.scripts

import com.fasterxml.jackson.databind.JsonNode
import org.apache.texera.amber.operator.metadata.OperatorMetadata
import org.apache.texera.docs.controllers.{ControllerContext, FormControllerBuilder}

import scala.jdk.CollectionConverters._

object OperatorFieldPlanner {

  final case class FieldInfo(
      key: String,
      isRequired: Boolean,
      autofill: Option[String],
      fieldType: Option[String],
      title: Option[String],
      description: Option[String]
  )

  def requiredKeys(metadata: OperatorMetadata): Seq[String] = {
    val required = metadata.jsonSchema.path("required")
    if (!required.isArray) return Seq.empty
    required.elements().asScala.map(_.asText()).filter(_.nonEmpty).toSeq
  }

  def autofillKeys(metadata: OperatorMetadata): Seq[String] = {
    fieldInfos(metadata)
      .filter(_.autofill.nonEmpty) // "autofill"
      .map(_.key)
      .distinct
  }

  def requiredAutofillKeys(metadata: OperatorMetadata): Seq[String] = {
    (requiredKeys(metadata) ++ autofillKeys(metadata)).distinct
    // Get all required field + with auto filled field
  }

  def fieldInfos(metadata: OperatorMetadata): Seq[FieldInfo] = {
    val required = requiredKeys(metadata).toSet
    val properties = metadata.jsonSchema.path("properties")
    if (!properties.isObject) return Seq.empty

    properties.fields().asScala.map { entry =>
      val key = entry.getKey
      val node = entry.getValue
      FieldInfo(
        key = key,
        isRequired = required.contains(key),
        autofill = textAt(node, "autofill"),
        fieldType = textAt(node, "type"),
        title = textAt(node, "title"),
        description = textAt(node, "description")
      )
    }.toSeq
  }

  def autoFillBuilder(
      ctx: ControllerContext,
      metadata: OperatorMetadata,
      defaultText: String = "test"
  ): FormControllerBuilder = {
    new FormControllerBuilder(ctx).autoFillFields(requiredAutofillKeys(metadata), defaultText)
  }

  private def textAt(node: JsonNode, field: String): Option[String] = {
    val v = node.path(field)
    if (v.isMissingNode || v.isNull) None
    else {
      val text = v.asText("").trim
      if (text.isEmpty) None else Some(text)
    }
  }
}
