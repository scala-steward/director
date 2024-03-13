package com.advancedtelematic.deviceregistry.data

import cats.syntax.either.*
import com.advancedtelematic.libats.codecs.CirceValidatedGeneric
import com.advancedtelematic.libats.data.{ValidatedGeneric, ValidationError}
import com.advancedtelematic.deviceregistry.data.GroupExpressionParser.parse
import io.circe.{Decoder, Encoder}

import scala.annotation.nowarn

@nowarn
final case class GroupExpression private (value: String) extends AnyVal {

  def droppingTag(tagId: TagId): Option[GroupExpression] =
    parse(value)
      .map(_.dropDeviceTag(tagId))
      .valueOr(throw _)
      .map(GroupExpressionAST.showExpression)
      .map(GroupExpression.from)
      .map(_.valueOr(throw _))

}

object GroupExpression {

  implicit val validatedGroupExpression: com.advancedtelematic.libats.data.ValidatedGeneric[
    com.advancedtelematic.deviceregistry.data.GroupExpression,
    String
  ] = new ValidatedGeneric[GroupExpression, String] {
    override def to(expression: GroupExpression): String = expression.value
    override def from(s: String): Either[ValidationError, GroupExpression] = GroupExpression.from(s)
  }

  def from(s: String): Either[ValidationError, GroupExpression] =
    if (s.length < 1 || s.length > 200)
      Left(ValidationError("The expression is too small or too big."))
    else
      GroupExpressionParser
        .parse(s)
        .fold(e => Left(ValidationError(e.desc)), _ => Right(new GroupExpression(s)))

  implicit val groupExpressionEncoder: Encoder[GroupExpression] =
    CirceValidatedGeneric.validatedGenericEncoder

  implicit val groupExpressionDecoder: Decoder[GroupExpression] =
    CirceValidatedGeneric.validatedGenericDecoder

}
