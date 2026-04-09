package org.apache.texera.amber.util

import com.fasterxml.jackson.annotation.{
  JsonIgnore,
  JsonIgnoreProperties,
  JsonProperty,
  JsonPropertyDescription,
  JsonValue
}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.kjetland.jackson.jsonSchema.annotations.{JsonSchemaInject, JsonSchemaTitle}
import org.apache.texera.amber.core.workflow.OutputPort
import org.apache.texera.amber.operator.LogicalOp
import org.apache.texera.amber.operator.metadata.annotations.{
  AutofillAttributeName,
  AutofillAttributeNameList,
  AutofillAttributeNameOnPort1
}

import com.fasterxml.jackson.annotation.JsonCreator

import java.lang.annotation.Annotation
import java.lang.reflect.{Field, Modifier, ParameterizedType}
import javax.validation.constraints.{NotNull, Size}

case class DocProperty(
    displayName: String,
    dataType: String,
    required: Boolean,
    defaultValue: String,
    description: String
)

case class EnumParam(
    name: String,
    paramType: String,
    defaultValue: Option[String] = None,
    description: String = ""
)

object JsonPropertyReflectionUtils {

  // ==================== Public API ====================

  /** Generate markdown table for input properties, with nested config fields inlined. */
  def generatePropertiesTable(clazz: Class[_], pageDepth: Int = 0): String = {
    val properties = extractProperties(clazz, pageDepth).sortBy(_.displayName.toLowerCase)
    val nestedTypes = findNestedConfigTypes(clazz).distinctBy(_._2.getName).toMap

    val header = "| Property | Requirement | Type | Default |     Description     |"
    val separator = "|----------|-------------|------|---------|----------------------|"

    val rows = properties.flatMap { prop =>
      val mainRow = formatPropertyRow(prop)

      // Check if this field has an eligible nested type to inline
      val nestedRows = nestedTypes.values
        .find(_.getSimpleName == extractNestedTypeName(prop.dataType))
        .map { nestedClass =>
          val nestedProps =
            extractProperties(nestedClass, pageDepth).sortBy(_.displayName.toLowerCase)
          nestedProps.map(np => formatNestedPropertyRow(prop.displayName, np))
        }
        .getOrElse(List.empty)

      mainRow :: nestedRows
    }

    (header :: separator :: rows).mkString("\n")
  }

  /** Generate output ports table (port index + mode). */
  def generateOutputSection(clazz: Class[_]): String = {
    try {
      createInstance(clazz) match {
        case Some(op: LogicalOp) =>
          val info = op.operatorInfo
          val ports = Option(info.outputPorts).getOrElse(List.empty)
          if (ports.isEmpty) return "*No output ports*"

          val builder = new StringBuilder()
          builder.append("### Output Ports\n\n")
          builder.append("| Port | Mode |\n")
          builder.append("|------|------|\n")

          ports.zipWithIndex.foreach {
            case (port, idx) =>
              val mode = formatOutputMode(port.mode)
              builder.append(s"| $idx | $mode |\n")
          }

          builder.toString()

        case None => "*Output information is unavailable (unable to instantiate operator)*"
        case _    => "*Output information is unavailable*"
      }
    } catch {
      case _: Throwable => "*Output information is unavailable*"
    }
  }

  /** Public: Get enum class for external use. */
  def getEnumClass(operatorClass: Class[_]): Option[Class[_]] = {
    getEnumClassInternal(operatorClass)
  }

  /** Public: Extract enum metadata for external use. */
  def extractEnumMetadata(enumClass: Class[_]): Option[List[EnumParam]] = {
    extractEnumMetadataInternal(enumClass)
  }

  // ==================== Input Properties Extraction ====================

  /** Extract documentable properties for a class, applying ignore rules. */
  private def extractProperties(clazz: Class[_], pageDepth: Int): List[DocProperty] = {
    val instance = createInstance(clazz)
    val allFields = getAllFields(clazz)
    val ignoredFields = getIgnoredFields(clazz)

    allFields
      .filterNot(f => ignoredFields.contains(f.getName))
      .filter(isDocumentableField)
      .distinctBy(_.getName)
      .map { field =>
        field.setAccessible(true)
        DocProperty(
          getDisplayName(field),
          getDataType(field, clazz, pageDepth),
          isRequired(field),
          getDefaultValue(field, instance),
          getDescription(field)
        )
      }
  }

  /** Collect class-level @JsonIgnoreProperties from the type hierarchy. */
  private def getIgnoredFields(clazz: Class[_]): Set[String] = {
    val ignored = scala.collection.mutable.Set[String]()
    var cur: Class[_] = clazz
    while (cur != null && cur != classOf[Object]) {
      val ann = cur.getAnnotation(classOf[JsonIgnoreProperties])
      if (ann != null) {
        ignored ++= ann.value()
      }
      cur = cur.getSuperclass
    }
    ignored.toSet
  }

  /** Identify nested config classes referenced by documentable fields. */
  private def findNestedConfigTypes(clazz: Class[_]): List[(String, Class[_])] = {
    val allFields = getAllFields(clazz)
    val ignoredFields = getIgnoredFields(clazz)

    allFields
      .filterNot(f => ignoredFields.contains(f.getName))
      .filter(isDocumentableField)
      .flatMap { field =>
        getNestedClass(field).filter(isEligibleNestedClass(clazz, _)).map(nc => field.getName -> nc)
      }
  }

  /** Extract a nested class for common container types like Option/List/Seq/Set. */
  private def getNestedClass(field: Field): Option[Class[_]] = {
    val rawType = field.getType
    val genericType = field.getGenericType

    def fromTypeArg(index: Int): Option[Class[_]] = {
      genericType match {
        case pt: ParameterizedType =>
          val args = pt.getActualTypeArguments
          if (args.length > index) {
            args(index) match {
              case cls: Class[_] => Some(cls)
              case _             => None
            }
          } else None
        case _ => None
      }
    }

    if (classOf[Option[_]].isAssignableFrom(rawType)) {
      fromTypeArg(0)
    } else if (
      classOf[scala.collection.Iterable[_]].isAssignableFrom(rawType) ||
      classOf[java.lang.Iterable[_]].isAssignableFrom(rawType)
    ) {
      fromTypeArg(0)
    } else if (
      classOf[scala.collection.Map[_, _]].isAssignableFrom(rawType) ||
      classOf[java.util.Map[_, _]].isAssignableFrom(rawType)
    ) {
      fromTypeArg(1)
    } else {
      None
    }
  }

  /** Determine whether a nested class should be expanded in docs. */
  private def isEligibleNestedClass(owner: Class[_], nested: Class[_]): Boolean = {
    if (nested.isEnum) return false
    if (nested == owner) return false
    val inStdLib =
      nested.getName.startsWith("java.") || nested.getName.startsWith("javax.") || nested.getName
        .startsWith("scala.")
    if (inStdLib) return false

    val fieldCount = getDocumentableFieldCount(nested)
    fieldCount > 0 && fieldCount <= 8
  }

  /** Count documentable fields for a nested config class. */
  private def getDocumentableFieldCount(clazz: Class[_]): Int = {
    val ignoredFields = getIgnoredFields(clazz)
    getAllFields(clazz)
      .filterNot(f => ignoredFields.contains(f.getName))
      .count(isDocumentableField)
  }

  /** Get all declared fields for a class, excluding overridden fields in parents. */
  private def getAllFields(clazz: Class[_]): List[Field] = {
    val chain = {
      val buf = scala.collection.mutable.ListBuffer[Class[_]]()
      var cur: Class[_] = clazz
      while (cur != null && cur != classOf[Object]) {
        buf += cur
        cur = cur.getSuperclass
      }
      buf.toList.reverse
    }

    val descendantNames = scala.collection.mutable.Map[Class[_], Set[String]]()
    var seenInChildren = Set.empty[String]
    chain.reverse.foreach { c =>
      descendantNames(c) = seenInChildren
      seenInChildren = seenInChildren ++ c.getDeclaredFields.map(_.getName).toSet
    }

    chain.flatMap { c =>
      val overriddenByChild = descendantNames.getOrElse(c, Set.empty)
      c.getDeclaredFields.toList.filterNot(f => overriddenByChild.contains(f.getName))
    }
  }

  /** Determine whether a field should be included in documentation. */
  private def isDocumentableField(field: Field): Boolean = {
    if (
      field.isSynthetic || Modifier.isStatic(field.getModifiers) ||
      field.getName.contains("$") || field.getName.startsWith("bitmap$")
    ) {
      return false
    }

    val hasDescription = findAnnotation(field, classOf[JsonPropertyDescription]).isDefined
    val hasTitle = findAnnotation(field, classOf[JsonSchemaTitle]).isDefined
    val hasJsonProperty = findAnnotation(field, classOf[JsonProperty]).isDefined
    val hasJsonIgnore = findAnnotation(field, classOf[JsonIgnore]).isDefined

    val internalFields = List(
      "operatorIdentifier",
      "operatorInfo",
      "inputPorts",
      "outputPorts",
      "operatorID",
      "operatorVersion",
      "fileTypeName",
      "context",
      "dummyPropertyList"
    )
    val isInternal = internalFields.exists(p => field.getName.equalsIgnoreCase(p))
    val isConstant = field.getName == field.getName.toUpperCase && field.getName.contains("_")

    val hasAnnotation = hasDescription || hasTitle || hasJsonProperty
    // Include unannotated fields if their inner type is an eligible nested config class
    val hasNestedConfig = !hasAnnotation && getNestedClass(field)
      .exists(nc => isEligibleNestedClass(field.getDeclaringClass, nc))

    (hasAnnotation || hasNestedConfig) && !hasJsonIgnore && !isInternal && !isConstant
  }

  /** Format a single property row for the markdown table. */
  private def formatPropertyRow(prop: DocProperty): String = {
    val name = s"**${prop.displayName}**"
    val requirement = if (prop.required) "✓" else ""
    val dataType = escapeMarkdown(simplifyContainerType(prop.dataType))
    val default = escapeMarkdown(prop.defaultValue)
    val description = escapeMarkdown(prop.description)
    s"| $name | $requirement | $dataType | $default | $description |"
  }

  /** Format an inlined nested property row with tree structure and muted color. */
  private def formatNestedPropertyRow(parentName: String, prop: DocProperty): String = {
    val name = s"""<span style="color:#888">&nbsp;&nbsp;└─ ${prop.displayName}</span>"""
    val requirement = if (prop.required) "✓" else ""
    val dataType = escapeMarkdown(prop.dataType)
    val default = escapeMarkdown(prop.defaultValue)
    val description = escapeMarkdown(prop.description)
    s"| $name | $requirement | $dataType | $default | $description |"
  }

  /** Simplify "List<FigureFactoryTableConfig>" to "List" when nested fields are inlined. */
  private def simplifyContainerType(dataType: String): String = {
    val containerPattern = "(List|Set|Map)<.+>".r
    containerPattern.findFirstIn(dataType) match {
      case Some(_) => dataType.replaceAll("<.+>", "")
      case None    => dataType
    }
  }

  /** Extract the inner type name from "List<FooConfig>" -> "FooConfig". */
  private def extractNestedTypeName(dataType: String): String = {
    val innerPattern = "(?:List|Set|Map)<(.+)>".r
    dataType match {
      case innerPattern(inner) => inner
      case _                   => ""
    }
  }

  // ==================== Type Inference ====================

  private def getDataType(field: Field, ownerClass: Class[_], pageDepth: Int): String = {
    val genericType = field.getGenericType
    val rawType = field.getType

    if (findAnnotation(field, classOf[AutofillAttributeNameList]).isDefined) {
      return "List<Column>"
    }

    if (
      findAnnotation(field, classOf[AutofillAttributeName]).isDefined ||
      findAnnotation(field, classOf[AutofillAttributeNameOnPort1]).isDefined
    ) {
      val constraints = getColumnTypeConstraints(field, ownerClass)
      return if (constraints.nonEmpty) s"Column (${constraints.mkString(", ")})" else "Column"
    }

    if (classOf[Option[_]].isAssignableFrom(rawType)) {
      return extractContentAsType(field)
        .orElse(extractGenericType(genericType, 0))
        .map(normalizeTypeName)
        .getOrElse("Object")
    }

    if (
      classOf[scala.collection.Map[_, _]].isAssignableFrom(rawType) ||
      classOf[java.util.Map[_, _]].isAssignableFrom(rawType)
    ) {
      val k = extractGenericType(genericType, 0).map(normalizeTypeName).getOrElse("K")
      val v = extractContentAsType(field)
        .orElse(extractGenericType(genericType, 1))
        .map(normalizeTypeName)
        .getOrElse("V")
      return s"Map<$k, $v>"
    }

    if (
      classOf[scala.collection.Set[_]].isAssignableFrom(rawType) ||
      classOf[java.util.Set[_]].isAssignableFrom(rawType)
    ) {
      val inner = extractContentAsType(field)
        .orElse(extractGenericType(genericType, 0))
        .map(normalizeTypeName)
        .getOrElse("Object")
      return s"Set<$inner>"
    }

    if (
      classOf[scala.collection.Iterable[_]].isAssignableFrom(rawType) ||
      classOf[java.lang.Iterable[_]].isAssignableFrom(rawType)
    ) {
      val inner = extractContentAsType(field)
        .orElse(extractGenericType(genericType, 0))
        .map(normalizeTypeName)
        .getOrElse("Object")

      if (inner == "Parameter") {
        extractHyperParameterList(ownerClass) match {
          case Some(params) if params.nonEmpty =>
            getEnumClassInternal(ownerClass) match {
              case Some(enumClass) =>
                val enumName = enumClass.getSimpleName
                // SklearnAdvancedKNNParameters -> sklearn-advanced-knn-parameters
                val fileName = enumName
                  .replaceAll("([a-z])([A-Z])", "$1-$2")
                  .toLowerCase

                val rootPath = "../" * (pageDepth + 1)
                return s"[$enumName](${rootPath}parameters/$fileName/)"
              case None =>
                return "Parameter"
            }
          case _ =>
            return "Parameter"
        }
      }

      return s"List<$inner>"
    }

    normalizeTypeName(rawType.getSimpleName) match {
      case name if name.endsWith("Method") => name.replace("Method", "")
      case name                            => name
    }
  }

  private def normalizeTypeName(name: String): String =
    name match {
      case "int" | "Integer"                  => "Integer"
      case "long" | "Long"                    => "Long"
      case "double" | "Double"                => "Double"
      case "float" | "Float"                  => "Float"
      case "boolean" | "Boolean"              => "Boolean"
      case n if n.contains("HyperParameters") => "Parameter"
      case n if n.contains("EncodableString") => "String"
      case other                              => other
    }

  private def extractGenericType(
      genericType: java.lang.reflect.Type,
      index: Int
  ): Option[String] = {
    genericType match {
      case pt: ParameterizedType =>
        val typeArgs = pt.getActualTypeArguments
        if (typeArgs.length > index) {
          Some(typeArgs(index).getTypeName.split('.').last.replace("$", ""))
        } else None
      case _ => None
    }
  }

  private def extractContentAsType(field: Field): Option[String] = {
    findAnnotation(field, classOf[JsonDeserialize])
      .flatMap(a => Option(a.contentAs(): Class[_]))
      .filterNot(c => c == null || c == classOf[Void] || c == classOf[Object])
      .map(_.getSimpleName)
  }

  // ==================== Column Type Constraints ====================

  /** Get accepted column types for a field from class-level attributeTypeRules. */
  private def getColumnTypeConstraints(field: Field, ownerClass: Class[_]): List[String] = {
    val jsonPropName = findAnnotation(field, classOf[JsonProperty])
      .flatMap(a => Option(a.value()).filter(_.nonEmpty))
      .getOrElse(field.getName)
    getAttributeTypeRules(ownerClass).getOrElse(jsonPropName, List.empty)
  }

  /**
    * Extract attributeTypeRules from class-level @JsonSchemaInject(json = "...").
    * Only handles simple cases: {"enum": [...]} and direct string values.
    */
  private def getAttributeTypeRules(clazz: Class[_]): Map[String, List[String]] = {
    var cur: Class[_] = clazz
    while (cur != null && cur != classOf[Object]) {
      val inject = cur.getAnnotation(classOf[JsonSchemaInject])
      if (inject != null) {
        val json = inject.json()
        if (json != null && json.nonEmpty) {
          try {
            val root = new ObjectMapper().readTree(json)
            val rules = root.get("attributeTypeRules")
            if (rules != null && rules.isObject) {
              val result = scala.collection.mutable.Map[String, List[String]]()
              val fields = rules.fields()
              while (fields.hasNext) {
                val entry = fields.next()
                val value = entry.getValue
                if (value.has("enum") && value.get("enum").isArray) {
                  val enumNode = value.get("enum")
                  result(entry.getKey) =
                    (0 until enumNode.size()).map(i => enumNode.get(i).asText()).toList
                } else if (value.isTextual) {
                  result(entry.getKey) = List(value.asText())
                }
              }
              return result.toMap
            }
          } catch {
            case _: Exception =>
          }
        }
      }
      cur = cur.getSuperclass
    }
    Map.empty
  }

  // ==================== HyperParameter Extraction ====================

  private def extractHyperParameterList(operatorClass: Class[_]): Option[List[EnumParam]] = {
    getEnumClassInternal(operatorClass).flatMap(extractEnumMetadataInternal)
  }

  private def getEnumClassInternal(operatorClass: Class[_]): Option[Class[_]] = {
    operatorClass.getGenericSuperclass match {
      case pt: ParameterizedType if pt.getActualTypeArguments.length > 0 =>
        try {
          val enumClassName = pt.getActualTypeArguments()(0).getTypeName
          Some(Class.forName(enumClassName))
        } catch {
          case _: ClassNotFoundException => findEnumByNamingConvention(operatorClass)
        }
      case _ => findEnumByNamingConvention(operatorClass)
    }
  }

  private def findEnumByNamingConvention(operatorClass: Class[_]): Option[Class[_]] = {
    val packageName = operatorClass.getPackage.getName
    val className = operatorClass.getSimpleName

    val possibleEnumNames = List(
      s"${packageName}.${className.replace("TrainerOpDesc", "").replace("OpDesc", "")}Parameters",
      s"${packageName}.SklearnAdvanced${className.replace("SklearnAdvanced", "").replace("TrainerOpDesc", "").replace("OpDesc", "")}Parameters"
    )

    possibleEnumNames.flatMap { enumName =>
      try {
        val enumClass = Class.forName(enumName)
        if (enumClass.isEnum) Some(enumClass) else None
      } catch {
        case _: ClassNotFoundException => None
      }
    }.headOption
  }

  private def extractEnumMetadataInternal(enumClass: Class[_]): Option[List[EnumParam]] = {
    if (!enumClass.isEnum) return None

    val constants = enumClass.getEnumConstants
    if (constants.isEmpty) return None

    // Strategy 1: getName() and getType() methods
    if (hasMethod(constants(0), "getName") && hasMethod(constants(0), "getType")) {
      return Some(constants.map { c =>
        EnumParam(invoke(c, "getName"), invoke(c, "getType"))
      }.toList)
    }

    // Strategy 2: name and type fields
    val fields = enumClass.getDeclaredFields
    val nameField =
      fields.find(f => f.getName.toLowerCase.contains("name") && !Modifier.isStatic(f.getModifiers))
    val typeField =
      fields.find(f => f.getName.toLowerCase.contains("type") && !Modifier.isStatic(f.getModifiers))

    if (nameField.isDefined && typeField.isDefined) {
      return extractWithFields(constants, nameField.get, typeField.get)
    }

    // Strategy 3: Enum constant names only
    Some(constants.map(c => EnumParam(c.toString, "unknown")).toList)
  }

  private def extractWithFields(
      constants: Array[_],
      nameField: Field,
      typeField: Field
  ): Option[List[EnumParam]] = {
    try {
      nameField.setAccessible(true)
      typeField.setAccessible(true)
      Some(constants.map { c =>
        val name = nameField.get(c).toString
        val paramType = typeField.get(c).toString
        EnumParam(name, paramType)
      }.toList)
    } catch {
      case _: Exception => None
    }
  }

  private def hasMethod(obj: Any, methodName: String): Boolean = {
    try {
      obj.getClass.getMethod(methodName)
      true
    } catch {
      case _: NoSuchMethodException => false
    }
  }

  private def invoke(obj: Any, methodName: String): String = {
    try {
      val method = obj.getClass.getMethod(methodName)
      method.invoke(obj).toString
    } catch {
      case _: Exception => "unknown"
    }
  }

  // ==================== Field Metadata ====================

  private def getDisplayName(field: Field): String = {
    val jsonName = findAnnotation(field, classOf[JsonProperty])
      .flatMap(a => Option(a.value()).filter(_.nonEmpty))
      .getOrElse(field.getName)

    findAnnotation(field, classOf[JsonSchemaTitle])
      .map(_.value())
      .filter(_.nonEmpty)
      .getOrElse(toTitleCase(jsonName))
  }

  private def isRequired(field: Field): Boolean = {
    val hasJsonRequired = findAnnotation(field, classOf[JsonProperty]).exists(_.required())
    val hasNotNull = findAnnotation(field, classOf[NotNull]).isDefined
    hasJsonRequired || hasNotNull
  }

  private def getDefaultValue(field: Field, instance: Option[AnyRef]): String = {
    findAnnotation(field, classOf[JsonProperty]).foreach { jsonProperty =>
      val defaultVal = jsonProperty.defaultValue()
      if (defaultVal != null && defaultVal.nonEmpty && defaultVal != "\u0000") {
        return defaultVal
      }
    }

    instance
      .flatMap(inst => readField(field, inst))
      .map(formatDefault)
      .getOrElse("-")
  }

  private def getDescription(field: Field): String = {
    val baseDesc = findAnnotation(field, classOf[JsonPropertyDescription])
      .map(_.value())
      .map(s => if (s.nonEmpty) s"${s.head.toUpper}${s.tail}" else s)
      .getOrElse("")

    val constraints = getConstraints(field)

    val parts = scala.collection.mutable.ListBuffer[String]()

    if (baseDesc.nonEmpty && constraints.nonEmpty) {
      parts += s"$baseDesc (${constraints.mkString(", ")})"
    } else if (baseDesc.nonEmpty) {
      parts += baseDesc
    } else if (constraints.nonEmpty) {
      parts += constraints.mkString(", ")
    }

    getEnumValues(field).foreach(v => parts += s"Options: $v")
    getHideCondition(field).foreach(h => parts += h)

    // Strip trailing punctuation before joining to avoid ".."
    parts.map(_.stripSuffix(".")).mkString(". ")
  }

  private def getConstraints(field: Field): List[String] = {
    val constraints = scala.collection.mutable.ListBuffer[String]()
    findAnnotation(field, classOf[Size]).foreach { size =>
      if (size.min() > 0) constraints += s"min: ${size.min()}"
      if (size.max() < Int.MaxValue) constraints += s"max: ${size.max()}"
      if (size.message().nonEmpty && !size.message().startsWith("{")) {
        constraints += size.message()
      }
    }
    constraints.toList
  }

  /** Extract enum constant display values for enum-typed fields. */
  private def getEnumValues(field: Field): Option[String] = {
    val rawType = field.getType
    if (!rawType.isEnum) return None

    val constants = rawType.getEnumConstants
    if (constants == null || constants.isEmpty) return None

    // Try @JsonValue-annotated method first for user-facing names
    val jsonValueMethod = rawType.getMethods.find { m =>
      m.getAnnotation(classOf[JsonValue]) != null && m.getParameterCount == 0
    }

    val values = jsonValueMethod match {
      case Some(method) =>
        constants.map(c =>
          try method.invoke(c).toString
          catch { case _: Exception => c.toString }
        )
      case None =>
        constants.map(_.toString)
    }

    Some(values.map(v => s"`$v`").mkString(", "))
  }

  /** Extract hide condition from @JsonSchemaInject annotations. */
  private def getHideCondition(field: Field): Option[String] = {
    findAnnotation(field, classOf[JsonSchemaInject]).flatMap { inject =>
      val strings = inject.strings()
      if (strings == null || strings.isEmpty) return None

      val strMap = strings.map(s => s.path() -> s.value()).toMap

      for {
        target <- strMap.get("hideTarget")
        hideType <- strMap.get("hideType")
        expected <- strMap.get("hideExpectedValue")
      } yield {
        hideType match {
          case "equals" => s"Hidden when `$target` = `$expected`"
          case "regex"  => s"Hidden when `$target` matches `$expected`"
          case _        => s"Conditional on `$target`"
        }
      }
    }
  }

  // ==================== Output Port Methods ====================

  private def formatOutputMode(mode: OutputPort.OutputMode): String = {
    mode.name match {
      case "SET_SNAPSHOT"    => "Set Snapshot"
      case "SET_DELTA"       => "Delta Updates"
      case "SINGLE_SNAPSHOT" => "Single Snapshot"
      case other             => other.replace("_", " ").toLowerCase.capitalize
    }
  }

  // ==================== Annotation Lookup ====================

  /**
    * Find annotation on a field, falling back to matching @JsonCreator constructor parameter.
    * Handles Scala var constructor params where annotations target PARAMETER not FIELD.
    */
  private def findAnnotation[T <: Annotation](field: Field, annotClass: Class[T]): Option[T] = {
    Option(field.getAnnotation(annotClass)).orElse {
      val owner = field.getDeclaringClass
      owner.getDeclaredConstructors
        .find(_.getAnnotation(classOf[JsonCreator]) != null)
        .flatMap { ctor =>
          ctor.getParameters.find { param =>
            val propAnn = param.getAnnotation(classOf[JsonProperty])
            val jsonName =
              if (propAnn != null && propAnn.value().nonEmpty) propAnn.value() else ""
            jsonName == field.getName || param.getName == field.getName
          }.flatMap(p => Option(p.getAnnotation(annotClass)))
        }
    }
  }

  // ==================== Utility Methods ====================

  private def createInstance(clazz: Class[_]): Option[AnyRef] = {
    try Some(clazz.getDeclaredConstructor().newInstance().asInstanceOf[AnyRef])
    catch { case _: Throwable => None }
  }

  private def readField(field: Field, instance: AnyRef): Option[Any] = {
    try Option(field.get(instance))
    catch { case _: Throwable => None }
  }

  private def formatDefault(value: Any): String =
    value match {
      case null                                      => "-"
      case xs: scala.collection.Seq[_] if xs.isEmpty => "-"
      case xs: scala.collection.Seq[_]               => s"[${xs.size} items]"
      case opt: Option[_] if opt.isEmpty             => "-"
      case opt: Option[_]                            => formatDefault(opt.get)
      case s: String if s.isEmpty                    => "-"
      case s: String if s.length > 50                => s.take(47) + "..."
      case s: String                                 => s
      case b: Boolean                                => s"`$b`"
      case other                                     => other.toString
    }

  private def toTitleCase(s: String): String = {
    s.replaceAll("([a-z])([A-Z])", "$1 $2")
      .replace('_', ' ')
      .split("\\s+")
      .filter(_.nonEmpty)
      .map(word => s"${word.head.toUpper}${word.tail}")
      .mkString(" ")
  }

  private def escapeMarkdown(s: String): String = {
    Option(s)
      .getOrElse("")
      .replace("\n", " ")
      .replace("\r", " ")
      .replace("|", "\\|")
  }
}
