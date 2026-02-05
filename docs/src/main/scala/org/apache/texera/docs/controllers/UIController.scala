// controllers/UiController.scala
package org.apache.texera.docs.controllers

import com.microsoft.playwright._

trait UiController {
  def execute(page: Page): Unit
}