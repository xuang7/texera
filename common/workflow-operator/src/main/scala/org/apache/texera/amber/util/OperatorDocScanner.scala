package org.apache.texera.amber.util

import org.apache.texera.amber.operator.LogicalOp
import org.apache.texera.amber.operator.metadata.{
  GroupInfo,
  OperatorMetadata,
  OperatorMetadataGenerator,
  OperatorGroupConstants
}

import java.io.{File, PrintWriter}

object OperatorDocScanner {

  def main(args: Array[String]): Unit = {
    val outputDir = if (args.nonEmpty) args(0) else "docs/generated/operators"
    generateAllDocs(outputDir)
  }

  // ==================== Case Classes ====================

  /** Information about a parameter enum and which operators use it */
  case class ParamInfo(
      enumName: String,
      parameters: List[EnumParam],
      operators: List[(String, String)] // (operatorName, groupPath)
  )

  // ==================== Public API ====================

  /**
    * Generate all documentation including operator pages, parameter pages, and indexes
    */
  def generateAllDocs(baseOutputDir: String = "docs"): Unit = {
    println("=" * 80)
    println("GENERATING COMPLETE DOCUMENTATION")
    println("=" * 80)

    // Clean and create base directory
    val baseDir = new File(baseOutputDir)
    if (baseDir.exists() && baseDir.isFile) {
      println(s"  Removing old file: $baseOutputDir")
      baseDir.delete()
    }
    if (!baseDir.exists()) {
      baseDir.mkdirs()
      println(s" Created directory: $baseOutputDir")
    }

    val allMetadata = OperatorMetadataGenerator.allOperatorMetadata
    val allOperators = allMetadata.operators

    println(s"\nFound ${allOperators.size} operators")

    // Group operators by their category path
    val grouped =
      allOperators.groupBy(m => convertGroupToPath(m.additionalMetadata.operatorGroupName))

    // 1. Generate individual operator pages in group directories
    generateIndividualPages(baseOutputDir, grouped)

    // 2. Generate individual parameter pages
    generateParameterPages(baseOutputDir, allOperators)

    // 3. Generate main index
    generateMainIndex(baseOutputDir, grouped, allMetadata.groups)

    generateMissingGroupIndexes(baseOutputDir, allMetadata.groups, grouped)

    println("\n" + "=" * 80)
    println(" DOCUMENTATION GENERATION COMPLETE")
    println(s" Output directory: $baseOutputDir")
    println(s" Total operator files: ${allOperators.size}")
    println(s" Total group directories: ${grouped.size}")
    println("=" * 80)
  }

  /**
    * Scan and print all operators to console (for testing)
    */
  def scanAllOperators(): Unit = {
    println("=" * 80)
    println("SCANNING ALL OPERATORS")
    println("=" * 80)

    val allMetadata = OperatorMetadataGenerator.allOperatorMetadata

    allMetadata.operators.sortBy(_.additionalMetadata.userFriendlyName).foreach { metadata =>
      println(s"\n### ${metadata.additionalMetadata.userFriendlyName}")
      println(s"Type: ${metadata.operatorType}")
      println(s"Group: ${metadata.additionalMetadata.operatorGroupName}")
      if (metadata.additionalMetadata.operatorDescription.nonEmpty) {
        println(s"Description: ${metadata.additionalMetadata.operatorDescription}")
      }
    }

    println("\n" + "=" * 80)
    println(s"TOTAL OPERATORS: ${allMetadata.operators.size}")
    println("=" * 80)
  }

  // ==================== Individual Operator Page Generation ====================

  /**
    * Generate individual markdown files for each operator in their respective group directories.
    *
    * Hugo structure:
    * - group pages: .../<group>/_index.md
    * - operator pages: .../<group>/<operator-slug>.md
    */
  private def generateIndividualPages(
      baseDir: String,
      grouped: Map[String, List[OperatorMetadata]]
  ): Unit = {
    println("\n Generating individual operator pages...")

    var totalCount = 0

    grouped.foreach {
      case (groupPath, operators) =>
        val dirPath = s"$baseDir/$groupPath"
        ensureDirectory(dirPath)

        operators.foreach { metadata =>
          val operatorClass = getOperatorClass(metadata.operatorType)

          val slug = slugify(metadata.additionalMetadata.userFriendlyName)
          val filePath = s"$dirPath/$slug.md"
          val content = generateOperatorMarkdown(metadata, operatorClass, groupPath)

          writeFile(filePath, content)
          totalCount += 1
        }

        // Generate group index page
        generateGroupIndex(baseDir, groupPath, operators)
    }

    println(s"  Generated $totalCount operator pages")
  }

  /**
    * Generate markdown content for a single operator page
    */
  private def generateOperatorMarkdown(
      metadata: OperatorMetadata,
      operatorClass: Class[_],
      groupPath: String
  ): String = {
    val markdown = new StringBuilder()

    try {
      val displayName = metadata.additionalMetadata.userFriendlyName
      val description = metadata.additionalMetadata.operatorDescription
      val groupName = metadata.additionalMetadata.operatorGroupName

      // YAML Front Matter
      markdown.append("---\n")
      markdown.append(s"""title: "${escapeYaml(displayName)}"""")
      markdown.append("\n")

      if (description.nonEmpty) {
        markdown.append(s"""description: "${escapeYaml(description)}"""")
        markdown.append("\n")
      }

      markdown.append(s"""category: "${escapeYaml(groupName)}"""")
      markdown.append("\n")
      markdown.append(s"""operator_type: "${escapeYaml(metadata.operatorType)}"""")
      markdown.append("\n")
      markdown.append(s"""version: "${escapeYaml(metadata.operatorVersion)}"""")
      markdown.append("\n")

      // Generate tags from group path
      val tags = groupPath.split("/").map(_.toLowerCase).mkString(", ")
      markdown.append(s"tags: [$tags]\n")
      markdown.append("---\n\n")

      // Breadcrumb navigation (without operator name — Hugo renders it as H1)
      val breadcrumbs = generateBreadcrumbs(groupPath)
      markdown.append(breadcrumbs)
      markdown.append("\n\n")

      val pageDepth = if (groupPath.trim.isEmpty) 0 else groupPath.split("/").length

      // Input Properties (nested config fields are inlined with ↳ prefix)
      val table = JsonPropertyReflectionUtils.generatePropertiesTable(operatorClass, pageDepth)
      val lines = table.split("\n")

      if (lines.length > 2) {
        markdown.append("### Input Properties\n\n")
        markdown.append(table)
        markdown.append("\n\n")
      }

      // Output section
      val outputSection = JsonPropertyReflectionUtils.generateOutputSection(operatorClass)
      markdown.append(outputSection)

    } catch {
      case e: Exception =>
        markdown.append(s"*Error generating documentation: ${e.getMessage}*\n\n")
    }

    markdown.toString()
  }

  /**
    * Generate breadcrumb navigation for an operator page
    *
    * Use directory links (Hugo-friendly): no "index.md"
    */
  private def generateBreadcrumbs(groupPath: String): String = {
    val parts = groupPath.split("/")
    val depth = parts.length

    // From operator page at docs/<group>/<subgroup>/operator-slug.md (rendered as .../operator-slug/)
    // we need (depth + 1) levels of ../ to reach the docs root
    val rootPath = "../" * (depth + 1)

    val breadcrumbs = new StringBuilder()
    breadcrumbs.append(s"[Home]($rootPath)")

    parts.zipWithIndex.foreach {
      case (part, i) =>
        val displayName = part.split("-").map(_.capitalize).mkString(" ")
        val levelsUp = depth - i
        val path = "../" * levelsUp
        breadcrumbs.append(s" > [$displayName]($path)")
    }

    breadcrumbs.toString()
  }

  // ==================== Group Index Generation ====================

  /**
    * Generate index page for a group showing all operators in that category
    */
  private def generateGroupIndex(
      baseDir: String,
      groupPath: String,
      operators: List[OperatorMetadata]
  ): Unit = {
    val markdown = new StringBuilder()

    val groupName = groupPath
      .split("/")
      .last
      .split("-")
      .map(_.capitalize)
      .mkString(" ")

    val parts = groupPath.split("/")
    val depth = parts.length

    // Calculate weight based on depth
    val weight = depth * 10

    // Generate tags from group path
    val tags = groupPath.split("/").map(_.toLowerCase).mkString(", ")

    // YAML Front Matter
    markdown.append("---\n")
    markdown.append(s"""title: "${escapeYaml(groupName)}"""")
    markdown.append("\n")
    markdown.append(s"""description: "Operators in the ${escapeYaml(groupName)} category"""")
    markdown.append("\n")
    markdown.append(s"weight: $weight\n")
    markdown.append("categories: [Operators]\n")
    markdown.append(s"tags: [$tags]\n")
    markdown.append("---\n\n")

    // Breadcrumb navigation (directory links)
    val rootPath = "../" * depth
    markdown.append(s"[Home]($rootPath)")

    if (parts.length > 1) {
      markdown.append(s" > [Parent](../)")
    }

    markdown.append(s" > $groupName\n\n")

    // Operators table
    if (operators.nonEmpty) {
      markdown.append("| Operator        | Description |\n")
      markdown.append("|-----------------|-------------|\n")

      operators.sortBy(_.additionalMetadata.userFriendlyName).foreach { metadata =>
        val displayName = metadata.additionalMetadata.userFriendlyName
        val description = if (metadata.additionalMetadata.operatorDescription.nonEmpty) {
          metadata.additionalMetadata.operatorDescription
        } else {
          "-"
        }

        val slug = slugify(displayName)

        // Link to operator directory (bundle): slug/
        markdown.append(s"| [$displayName]($slug/) | $description |\n")
      }

      markdown.append(s"\n**Total**: ${operators.size} operators\n")
    } else {
      markdown.append("*No operators in this category.*\n")
    }

    val indexPath = s"$baseDir/$groupPath/_index.md"
    writeFile(indexPath, markdown.toString())
  }

  // ==================== Main Index Generation ====================

  /**
    * Generate main index page with all categories and operator counts
    *
    * Hugo section root should be _index.md
    */
  private def generateMainIndex(
      baseDir: String,
      grouped: Map[String, List[OperatorMetadata]],
      groups: List[org.apache.texera.amber.operator.metadata.GroupInfo]
  ): Unit = {
    println("\n Generating main index...")

    val markdown = new StringBuilder()

    // YAML Front Matter
    markdown.append("---\n")
    markdown.append("title: \"Texera Operators Documentation\"\n")
    markdown.append(
      "description: \"Complete reference for all Texera operators organized by category\"\n"
    )
    markdown.append("weight: 1\n")
    markdown.append("categories: [Documentation]\n")
    markdown.append("tags: [operators, reference, index]\n")
    markdown.append("---\n\n")

    markdown.append("# Texera Operators Documentation\n\n")
    markdown.append(s"**Generated**: ${java.time.LocalDateTime.now()}\n\n")
    markdown.append(s"**Total Operators**: ${grouped.values.map(_.size).sum}\n\n")
    markdown.append("---\n\n")

    markdown.append("## Quick Links\n\n")
    // Link to section directory instead of parameters/index.md
    markdown.append("- [Parameter Reference](parameters/) - ML parameter specifications\n\n")
    markdown.append("---\n\n")

    markdown.append("## Operator Categories\n\n")

    groups.foreach { groupInfo =>
      generateGroupSection(markdown, groupInfo, grouped, 0)
    }

    // Root section index should be _index.md
    writeFile(s"$baseDir/_index.md", markdown.toString())
    println(s"   Generated main index")
  }

  /**
    * Recursively generate group sections in the main index
    */
  private def generateGroupSection(
      markdown: StringBuilder,
      groupInfo: org.apache.texera.amber.operator.metadata.GroupInfo,
      grouped: Map[String, List[OperatorMetadata]],
      level: Int
  ): Unit = {
    val indent = "  " * level
    val groupPath = convertGroupToPath(groupInfo.groupName)
    val operatorCount = grouped.getOrElse(groupPath, List.empty).size

    // Link to section directory (not .../index.md)
    markdown.append(s"$indent- [${groupInfo.groupName}]($groupPath/)")
    if (operatorCount > 0) {
      markdown.append(s" ($operatorCount operators)")
    }
    markdown.append("\n")

    if (groupInfo.children != null) {
      groupInfo.children.foreach { subGroup =>
        generateGroupSection(markdown, subGroup, grouped, level + 1)
      }
    }
  }

  // ==================== Parameter Pages Generation ====================

  /**
    * Generate individual parameter pages and parameter index
    */
  private def generateParameterPages(
      baseDir: String,
      allOperators: List[OperatorMetadata]
  ): Unit = {
    println("\n Generating parameter pages...")

    val params = scala.collection.mutable.Map[String, ParamInfo]()

    // Collect all parameter enums from operators
    allOperators.foreach { metadata =>
      val operatorClass = getOperatorClass(metadata.operatorType)
      val opName = metadata.additionalMetadata.userFriendlyName
      val opPath = convertGroupToPath(metadata.additionalMetadata.operatorGroupName)

      JsonPropertyReflectionUtils.getEnumClass(operatorClass) match {
        case Some(enumClass) =>
          val enumName = enumClass.getSimpleName

          if (!params.contains(enumName)) {
            // First time seeing this enum
            JsonPropertyReflectionUtils.extractEnumMetadata(enumClass) match {
              case Some(p) =>
                params(enumName) = ParamInfo(enumName, p, List((opName, opPath)))
              case None =>
            }
          } else {
            // Add this operator to the existing enum's usage list
            val info = params(enumName)
            params(enumName) = info.copy(
              operators = (info.operators :+ (opName, opPath)).distinct
            )
          }
        case None =>
      }
    }

    if (params.isEmpty) {
      println("     No parameter enums found")
      return
    }

    // Create parameters directory
    val paramDir = s"$baseDir/parameters"
    ensureDirectory(paramDir)

    // Generate individual parameter page for each enum (keep as single .md files to minimize changes)
    params.foreach {
      case (enumName, info) =>
        val fileName = enumName
          .replaceAll("([a-z])([A-Z])", "$1-$2")
          .toLowerCase + ".md"

        val content = generateParameterMarkdown(info)
        writeFile(s"$paramDir/$fileName", content)
    }

    // Generate parameter index page (use _index.md for section)
    generateParameterIndexMarkdown(baseDir, params.toMap)

    println(s"    Generated ${params.size} parameter pages")
  }

  /**
    * Generate markdown content for a single parameter page
    */
  private def generateParameterMarkdown(info: ParamInfo): String = {
    val markdown = new StringBuilder()

    val displayName = info.enumName.replaceAll("Parameters$", "")

    // YAML Front Matter
    markdown.append("---\n")
    markdown.append(s"""title: "${escapeYaml(displayName)} Parameters"""")
    markdown.append("\n")
    markdown.append(s"""description: "Parameter specifications for ${escapeYaml(
      displayName
    )} operators"""")
    markdown.append("\n")
    markdown.append("categories: [Parameters]\n")
    markdown.append("tags: [ml, sklearn, parameters]\n")
    markdown.append("---\n\n")

    // From /parameters/<page>/, "../" goes back to /parameters/
    markdown.append("[← Parameters Index](../)\n\n")
    markdown.append(s"# $displayName Parameters\n\n")

    // List of operators using this parameter set
    if (info.operators.nonEmpty) {
      markdown.append("## Used By\n\n")
      markdown.append("This parameter set is used by the following operators:\n\n")

      info.operators.sortBy(_._1).foreach {
        case (name, path) =>
          val slug = slugify(name)
          // Operator pages are now bundles: ../<path>/<slug>/
          markdown.append(s"- [$name](../$path/$slug/)\n")
      }
      markdown.append("\n")
    }

    // Parameters table
    markdown.append("## Parameters\n\n")
    markdown.append("| Parameter | Type |\n")
    markdown.append("|-----------|------|\n")

    info.parameters.foreach { p =>
      markdown.append(s"| **${p.name}** | `${p.paramType}` |\n")
    }

    markdown.toString()
  }

  /**
    * Generate parameter index page listing all parameter sets
    */
  private def generateParameterIndexMarkdown(
      baseDir: String,
      params: Map[String, ParamInfo]
  ): Unit = {
    val markdown = new StringBuilder()

    // YAML Front Matter
    markdown.append("---\n")
    markdown.append("title: \"Parameter Reference\"\n")
    markdown.append(
      "description: \"Complete reference for machine learning operator parameters\"\n"
    )
    markdown.append("weight: 5\n")
    markdown.append("categories: [Reference]\n")
    markdown.append("tags: [parameters, ml, reference]\n")
    markdown.append("---\n\n")

    // From /parameters/, "../" goes back to root section
    markdown.append("[← Home](../)\n\n")
    markdown.append("# Parameter Reference\n\n")
    markdown.append("Complete specifications for machine learning operator parameters.\n\n")
    markdown.append("---\n\n")

    markdown.append("## Available Parameter Sets\n\n")
    markdown.append("| Parameter Set | Used By | Operators |\n")
    markdown.append("|---------------|---------|----------|\n")

    params.toSeq.sortBy(_._1).foreach {
      case (enumName, info) =>
        val displayName = enumName.replaceAll("Parameters$", "")
        val fileName = enumName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase + ".md"
        val opCount = info.operators.size
        val opList = info.operators.map(_._1).sorted.take(3).mkString(", ")
        val more = if (info.operators.size > 3) s", +${info.operators.size - 3} more" else ""

        markdown.append(s"| [$displayName]($fileName) | $opCount | $opList$more |\n")
    }

    // parameters is a section: _index.md
    writeFile(s"$baseDir/parameters/_index.md", markdown.toString())
  }

  // ==================== Helper Methods ====================

  private def escapeYaml(s: String): String = {
    s.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  private def slugify(name: String): String = {
    name.trim
      .replaceAll("\\s+", "-")
      .replaceAll("[^a-zA-Z0-9-]", "")
      .toLowerCase
  }

  /**
    * Dynamically built map from group name to file system path,
    * derived from OperatorGroupConstants.OperatorGroupOrderList.
    */
  private lazy val groupPathMap: Map[String, String] = {
    def walk(groups: List[GroupInfo], prefix: String): Map[String, String] = {
      groups.flatMap { g =>
        val segment = slugify(g.groupName)
        val path = if (prefix.isEmpty) segment else s"$prefix/$segment"
        val current = Map(g.groupName -> path)
        val children = Option(g.children).getOrElse(List.empty)
        current ++ walk(children, path)
      }.toMap
    }
    walk(OperatorGroupConstants.OperatorGroupOrderList, "")
  }

  private def convertGroupToPath(groupName: String): String = {
    groupPathMap.getOrElse(groupName, slugify(groupName))
  }

  /**
    * Reverse map from operator type string to operator class, built once.
    */
  private lazy val reverseOperatorTypeMap: Map[String, Class[_ <: LogicalOp]] =
    OperatorMetadataGenerator.operatorTypeMap.map { case (clazz, typeName) => typeName -> clazz }

  private def getOperatorClass(operatorType: String): Class[_] = {
    reverseOperatorTypeMap.getOrElse(
      operatorType,
      throw new RuntimeException(s"Operator type not found: $operatorType")
    )
  }

  /**
    * Ensure directory exists, removing any conflicting file
    */
  private def ensureDirectory(path: String): Unit = {
    val dir = new File(path)

    if (dir.exists() && dir.isFile) {
      println(s"️  Removing conflicting file: $path")
      dir.delete()
    }

    if (!dir.exists()) {
      dir.mkdirs()
    }
  }

  /**
    * Write content to file, creating parent directories as needed
    */
  private def writeFile(path: String, content: String): Unit = {
    val file = new File(path)
    val parent = file.getParentFile

    if (parent != null) {
      ensureDirectory(parent.getPath)
    }

    val writer = new PrintWriter(file)
    try {
      writer.write(content)
    } finally {
      writer.close()
    }
  }

  private def generateMissingGroupIndexes(
      baseDir: String,
      groups: List[org.apache.texera.amber.operator.metadata.GroupInfo],
      grouped: Map[String, List[OperatorMetadata]]
  ): Unit = {

    def rec(
        g: org.apache.texera.amber.operator.metadata.GroupInfo,
        parentPath: Option[String]
    ): Unit = {
      val groupPath = convertGroupToPath(g.groupName)
      val dirPath = s"$baseDir/$groupPath"
      ensureDirectory(dirPath)

      val indexPath = s"$dirPath/_index.md"

      // Operators directly under THIS groupPath (might be empty for parent groups)
      val opsHere = grouped.getOrElse(groupPath, Nil)

      // Always generate/overwrite to include both subcategory links and operator tables
      val title = g.groupName
      val depth = groupPath.split("/").length
      val rootPath = "../" * depth

      val md = new StringBuilder()
      md.append("---\n")
      md.append(s"""title: "${escapeYaml(title)}"""").append("\n")
      md.append(s"""description: "Operators in the ${escapeYaml(title)} category"""").append("\n")
      md.append(s"weight: ${depth * 10}\n")
      md.append("categories: [Operators]\n")
      md.append(s"tags: [${groupPath.split("/").mkString(", ")}]\n")
      md.append("---\n\n")

      // Breadcrumb
      md.append(s"[Home]($rootPath)")
      parentPath.foreach(_ => md.append(" > [Parent](../)"))
      md.append(s" > $title\n\n")

      // Child groups list (so parent pages like machine-learning/ are useful)
      if (g.children != null && g.children.nonEmpty) {
        md.append("## Subcategories\n\n")
        g.children.foreach { c =>
          val childPath = convertGroupToPath(c.groupName)
          val childDirName = childPath.stripPrefix(groupPath + "/")
          md.append(s"- [${c.groupName}]($childDirName/)\n")
        }
        md.append("\n")
      }

      // Operators directly under this group (if any)
      if (opsHere.nonEmpty) {
        md.append("## Operators\n\n")
        md.append("| Operator | Description |\n")
        md.append("|----------|-------------|\n")
        opsHere.sortBy(_.additionalMetadata.userFriendlyName).foreach { metadata =>
          val name = metadata.additionalMetadata.userFriendlyName
          val desc = Option(metadata.additionalMetadata.operatorDescription)
            .filter(_.nonEmpty)
            .getOrElse("-")
          val slug = slugify(name)
          md.append(s"| [$name]($slug/) | $desc |\n")
        }
        md.append(s"\n**Total**: ${opsHere.size} operators\n")
      }

      writeFile(indexPath, md.toString())

      // Recurse
      if (g.children != null) {
        g.children.foreach(c => rec(c, Some(groupPath)))
      }
    }

    groups.foreach(g => rec(g, None))
  }

}
