package org.apache.texera.docs.controllers

import com.microsoft.playwright.Page
import scala.collection.mutable.ArrayBuffer

/**
 * Shared context wrapping Playwright Page, passed through all builders.
 */
class ControllerContext(val page: Page) {
  private var _fakeCursorInstalled: Boolean = false

  def ensureFakeCursor(): Unit = {
    if (!_fakeCursorInstalled) {
      Utils.installFakeCursor(page)
      _fakeCursorInstalled = true
    }
  }
}

/** A single named step that runs against a ControllerContext. */
trait ControllerStep {
  def name: String
  def run(ctx: ControllerContext): Unit
}

object ControllerStep {
  def apply(stepName: String)(action: ControllerContext => Unit): ControllerStep =
    new ControllerStep {
      override def name: String = stepName
      override def run(ctx: ControllerContext): Unit = action(ctx)
    }
}

/** An immutable, ordered list of steps ready to execute. */
class Controller(val steps: Seq[ControllerStep]) {
  def execute(ctx: ControllerContext): Unit = {
    steps.foreach { step =>
      println(s"[${step.name}] Executing...")
      step.run(ctx)
      println(s"[${step.name}] Done")
    }
  }
}

/**
 * Base builder — subclasses accumulate steps via fluent API, then execute().
 *
 * Usage:
 *   new LoginControllerBuilder(ctx).login("u","p").logout().execute()
 */
abstract class ControllerBuilder(protected val context: ControllerContext) {
  private val steps: ArrayBuffer[ControllerStep] = ArrayBuffer.empty

  protected def addStep(step: ControllerStep): this.type = {
    steps += step
    this
  }

  def execute(): Unit = {
    steps.foreach { step =>
      println(s"[${step.name}] Executing...")
      step.run(context)
      println(s"[${step.name}] Done")
    }
  }
}
