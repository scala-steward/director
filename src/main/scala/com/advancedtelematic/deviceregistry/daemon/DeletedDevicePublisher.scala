package com.advancedtelematic.deviceregistry.daemon

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.advancedtelematic.deviceregistry.data.DataType.DeletedDevice
import com.advancedtelematic.deviceregistry.db.DeviceRepository
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.Messages.DeleteDeviceRequest
import slick.jdbc.MySQLProfile.api.*

import scala.concurrent.{ExecutionContext, Future}

class DeletedDevicePublisher(messageBus: MessageBusPublisher)(
  implicit val db: Database,
  val mat: Materializer,
  val ec: ExecutionContext) {

  private def publishDeleteDevice(deletedDevice: DeletedDevice): Future[Unit] =
    messageBus.publish(DeleteDeviceRequest(deletedDevice.namespace, deletedDevice.uuid))

  def run(): Future[Done] =
    Source
      .fromPublisher(db.stream(DeviceRepository.deletedDevices.result))
      .mapAsync(20)(publishDeleteDevice)
      .runWith(Sink.ignore)

}
