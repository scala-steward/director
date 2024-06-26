package com.advancedtelematic.director.db

import com.advancedtelematic.director.data.AdminDataType.TargetUpdate
import com.advancedtelematic.director.data.Codecs.*
import com.advancedtelematic.director.data.DataType.{AdminRoleName, ScheduledUpdate}
import com.advancedtelematic.libats.data.DataType.HashMethod
import com.advancedtelematic.libats.data.DataType.HashMethod.HashMethod
import com.advancedtelematic.libats.slick.codecs.SlickEnumeratum.enumeratumMapper
import com.advancedtelematic.libats.slick.db.SlickCirceMapper
import com.advancedtelematic.libtuf.data.ValidatedString.{
  ValidatedString,
  ValidatedStringValidation
}
import slick.jdbc.MySQLProfile.api.*

import java.time.Instant
import scala.reflect.ClassTag

object SlickMapping {

  import com.advancedtelematic.libats.slick.codecs.SlickEnumMapper
  import com.advancedtelematic.libtuf.data.TufDataType.TargetFormat

  implicit val hashMethodColumn: BaseColumnType[HashMethod] =
    MappedColumnType.base[HashMethod, String](_.toString, HashMethod.withName)

  implicit val targetFormatMapper: BaseColumnType[TargetFormat.Value] =
    SlickEnumMapper.enumMapper(TargetFormat)

  implicit val targetUpdateMapper: BaseColumnType[TargetUpdate] =
    SlickCirceMapper.circeMapper[TargetUpdate]

  private def validatedStringMapper[W <: ValidatedString: ClassTag](
    implicit validation: ValidatedStringValidation[W]) =
    MappedColumnType.base[W, String](
      _.value,
      validation.apply(_).valueOr(err => throw new IllegalArgumentException(err.toList.mkString))
    )

  implicit val adminRoleNameMapper: BaseColumnType[AdminRoleName] =
    validatedStringMapper[AdminRoleName]

  implicit val scheduledUpdatesMapper: BaseColumnType[ScheduledUpdate.Status] = enumeratumMapper(
    ScheduledUpdate.Status
  )

  implicit def instantOrdering: Ordering[Instant] = Ordering.fromLessThan(_ isBefore _)
}
