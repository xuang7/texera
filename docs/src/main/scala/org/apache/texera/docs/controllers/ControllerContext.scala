// controllers/ControllerContext.scala
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
 * Base builder — subclasses accumulate steps via fluent API, then build() / execute().
 *
 * Usage:
 *   new XxxControllerBuilder(ctx).stepA(...).stepB(...).build().execute(ctx)
 *   // or shorthand:
 *   new XxxControllerBuilder(ctx).stepA(...).stepB(...).execute()
 */
trait ControllerBuilder[B <: ControllerBuilder[B]] {
  protected val context: ControllerContext
  protected val steps: ArrayBuffer[ControllerStep] = ArrayBuffer.empty
  private var builtController: Option[Controller] = None

  protected def self: B

  protected def addStep(step: ControllerStep): B = {
    require(builtController.isEmpty, "Cannot add steps after build() has been called")
    steps += step
    self
  }

  def build(): Controller = {
    builtController.getOrElse {
      val controller = new Controller(steps.toVector)
      builtController = Some(controller)
      controller
    }
  }

  /** Convenience: build + execute in one call. */
  def execute(): Unit = build().execute(context)
}
