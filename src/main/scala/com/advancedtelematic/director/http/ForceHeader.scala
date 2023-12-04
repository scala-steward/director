package com.advancedtelematic.director.http

import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}

import scala.util.Try

final class ForceHeader(value: Boolean) extends ModeledCustomHeader[ForceHeader] {
  override def renderInRequests = true
  override def renderInResponses = true
  override val companion : com.advancedtelematic.director.http.ForceHeader.type= ForceHeader
  override def value: String = value.toString
  def asBoolean: Boolean = value
}
object ForceHeader extends ModeledCustomHeaderCompanion[ForceHeader] {
  override val name = "x-trx-force"
  override def parse(value: String): Try[ForceHeader] = Try(new ForceHeader(value == "true"))
}
