package org.apache.texera.docs.autofix

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.apache.texera.docs.scripts.OperatorFieldValues

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PatchApplier {

  private val mapper = new ObjectMapper()
  private val configPath = OperatorFieldValues.configFile

  /** Snapshot current operator config; returns None if not present. */
  def snapshot(operatorType: String): Option[JsonNode] = {
    val root = readRoot()
    val node = root.path("operators").path(operatorType)
    if (node.isMissingNode || node.isNull) None else Some(node.deepCopy[JsonNode])
  }

  /** Apply patch to an in-memory copy of the operator's config; returns the new config. */
  def dryRun(env: PatchEnvelope): Either[String, JsonNode] = {
    val root = readRoot()
    val current = root.path("operators").path(env.operatorType) match {
      case n if n.isMissingNode || n.isNull => mapper.createObjectNode().asInstanceOf[JsonNode]
      case n                                 => n.deepCopy[JsonNode]
    }
    applyOps(current, env.ops)
  }

  /** Apply patch, write to disk, refresh OperatorFieldValues cache. */
  def apply(env: PatchEnvelope): Either[String, Unit] = {
    val root = readRoot()
    val operatorsNode = root.path("operators") match {
      case n: ObjectNode => n
      case _ =>
        return Left("operators node missing or not an object")
    }
    val current: JsonNode = operatorsNode.path(env.operatorType) match {
      case n if n.isMissingNode || n.isNull => mapper.createObjectNode().asInstanceOf[JsonNode]
      case n                                 => n.deepCopy[JsonNode]
    }
    applyOps(current, env.ops) match {
      case Left(err) => Left(err)
      case Right(patched) =>
        operatorsNode.set[ObjectNode](env.operatorType, patched)
        writeRoot(root)
        OperatorFieldValues.reload()
        Right(())
    }
  }

  /** Restore a previously snapshotted config for the operator. */
  def restore(operatorType: String, snapshot: Option[JsonNode]): Unit = {
    val root = readRoot()
    val operatorsNode = root.path("operators") match {
      case n: ObjectNode => n
      case _             => return
    }
    snapshot match {
      case Some(node) => operatorsNode.set[ObjectNode](operatorType, node)
      case None       => operatorsNode.remove(operatorType)
    }
    writeRoot(root)
    OperatorFieldValues.reload()
  }

  private def readRoot(): ObjectNode = {
    if (!Files.exists(configPath)) {
      throw new IllegalStateException(s"operator-field-values.json not found: $configPath")
    }
    mapper.readTree(configPath.toFile) match {
      case obj: ObjectNode => obj
      case _ => throw new IllegalStateException("operator-field-values.json root is not an object")
    }
  }

  private def writeRoot(root: ObjectNode): Unit = {
    val out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    Files.write(configPath, out.getBytes(StandardCharsets.UTF_8))
  }

  // ===== RFC 6902 subset: replace, add, remove =====

  private def applyOps(root: JsonNode, ops: Seq[PatchOp]): Either[String, JsonNode] = {
    var current: JsonNode = root
    ops.foreach { op =>
      applyOp(current, op) match {
        case Left(err)    => return Left(err)
        case Right(next)  => current = next
      }
    }
    Right(current)
  }

  private def applyOp(root: JsonNode, op: PatchOp): Either[String, JsonNode] = {
    val tokens = parsePointer(op.path)
    if (tokens.isEmpty) {
      op.op match {
        case "replace" | "add" => op.value match {
          case Some(v) => Right(v.deepCopy[JsonNode])
          case None    => Left(s"missing value for ${op.op} at root")
        }
        case "remove" => Left("cannot remove root")
        case other    => Left(s"unsupported op: $other")
      }
    } else {
      try {
        val mutable = root match {
          case _: ObjectNode | _: ArrayNode => root
          case _                            => return Left(s"cannot patch into non-container at path ${op.path}")
        }
        op.op match {
          case "replace" => op.value match {
            case Some(v) => setAtPointer(mutable, tokens, v, addMode = false); Right(mutable)
            case None    => Left(s"missing value for replace at ${op.path}")
          }
          case "add" => op.value match {
            case Some(v) => setAtPointer(mutable, tokens, v, addMode = true); Right(mutable)
            case None    => Left(s"missing value for add at ${op.path}")
          }
          case "remove" => removeAtPointer(mutable, tokens); Right(mutable)
          case other     => Left(s"unsupported op: $other")
        }
      } catch {
        case e: Exception => Left(s"failed to apply ${op.op} at ${op.path}: ${e.getMessage}")
      }
    }
  }

  private def parsePointer(path: String): Seq[String] = {
    if (path.isEmpty || path == "/") return Seq.empty
    val trimmed = if (path.startsWith("/")) path.drop(1) else path
    trimmed.split("/", -1).toSeq.map { tok =>
      tok.replace("~1", "/").replace("~0", "~")
    }
  }

  private def setAtPointer(root: JsonNode, tokens: Seq[String], value: JsonNode, addMode: Boolean): Unit = {
    val parent = navigate(root, tokens.init, createMissing = addMode)
    val last = tokens.last
    parent match {
      case obj: ObjectNode =>
        obj.set[ObjectNode](last, value.deepCopy[JsonNode])
      case arr: ArrayNode =>
        if (last == "-") arr.add(value.deepCopy[JsonNode])
        else {
          val idx = last.toInt
          if (addMode) {
            if (idx >= arr.size()) arr.add(value.deepCopy[JsonNode])
            else arr.insert(idx, value.deepCopy[JsonNode])
          } else {
            arr.set(idx, value.deepCopy[JsonNode])
          }
        }
      case other =>
        throw new IllegalArgumentException(s"parent is not container: ${other.getNodeType}")
    }
  }

  private def removeAtPointer(root: JsonNode, tokens: Seq[String]): Unit = {
    val parent = navigate(root, tokens.init, createMissing = false)
    val last = tokens.last
    parent match {
      case obj: ObjectNode => obj.remove(last)
      case arr: ArrayNode  => arr.remove(last.toInt)
      case _                => throw new IllegalArgumentException("parent not container")
    }
  }

  private def navigate(root: JsonNode, tokens: Seq[String], createMissing: Boolean): JsonNode = {
    var cur: JsonNode = root
    tokens.foreach { tok =>
      cur = cur match {
        case obj: ObjectNode =>
          if (!obj.has(tok)) {
            if (createMissing) {
              val child = mapper.createObjectNode()
              obj.set[ObjectNode](tok, child)
              child
            } else throw new IllegalArgumentException(s"path token '$tok' not found")
          } else obj.get(tok)
        case arr: ArrayNode =>
          val idx = tok.toInt
          if (idx >= arr.size()) {
            if (createMissing) {
              val child = mapper.createObjectNode()
              while (arr.size() < idx) arr.add(mapper.createObjectNode())
              arr.add(child)
              child
            } else throw new IllegalArgumentException(s"array index $idx out of bounds")
          } else arr.get(idx)
        case other =>
          throw new IllegalArgumentException(s"cannot descend into ${other.getNodeType} at token '$tok'")
      }
    }
    cur
  }
}
