package com.advancedtelematic.director.deviceregistry.daemon

import cats.syntax.show.*
import com.advancedtelematic.director.db.deviceregistry.{DeviceRepository, EcuReplacementRepository}
import com.advancedtelematic.libats.messaging.MsgOperation.MsgOperation
import com.advancedtelematic.libats.messaging_datatype.Messages.{
  EcuReplacement,
  EcuReplacementFailed
}
import com.advancedtelematic.director.http.deviceregistry.Errors.MissingDevice
import com.advancedtelematic.director.deviceregistry.data.DeviceStatus
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

class EcuReplacementListener()(implicit db: Database, ec: ExecutionContext)
    extends MsgOperation[EcuReplacement] {
  private val _log = LoggerFactory.getLogger(this.getClass)

  override def apply(msg: EcuReplacement): Future[Unit] = {
    val action = for {
      _ <- EcuReplacementRepository.insert(msg)
      _ <- msg match {
        case _: EcuReplacementFailed =>
          DeviceRepository.setDeviceStatus(msg.deviceUuid, DeviceStatus.Error)
        case _ => DBIO.successful(())
      }
    } yield ()

    db.run(action).recover { case MissingDevice =>
      _log.warn(
        s"Trying to replace ECUs on a non-existing or deleted device: ${msg.deviceUuid.show}."
      )
    }
  }

}
