package com.advancedtelematic.director.http

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.*
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import cats.implicits.*
import com.advancedtelematic.director.data.AdminDataType.AssignUpdateRequest
import com.advancedtelematic.director.data.Codecs.*
import com.advancedtelematic.director.http.DeviceAssignments.AssignmentCreateResult
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyAkka.*
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.Messages.{
  DeviceUpdateAssigned,
  DeviceUpdateEvent
}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import slick.jdbc.MySQLProfile.api.Database

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class AssignmentsResource(extractNamespace: Directive1[Namespace])(
  implicit val db: Database,
  val ec: ExecutionContext,
  messageBusPublisher: MessageBusPublisher) {

  import Directives.*

  val deviceAssignments = new DeviceAssignments()

  private def createAssignments(ns: Namespace,
                                req: AssignUpdateRequest): Future[AssignmentCreateResult] = {
    val assignments =
      deviceAssignments.createForDevices(ns, req.correlationId, req.devices, req.mtuId)

    assignments.flatMap { createResult =>
      createResult.affected.toList
        .traverse_ { deviceId =>
          val msg: DeviceUpdateEvent =
            DeviceUpdateAssigned(ns, Instant.now(), req.correlationId, deviceId)
          messageBusPublisher.publishSafe(msg)
        }
        .map(_ => createResult)
    }
  }

  private implicit val updateIdUnmarshaller: akka.http.scaladsl.unmarshalling.Unmarshaller[
    String,
    com.advancedtelematic.libats.messaging_datatype.DataType.UpdateId
  ] = UpdateId.unmarshaller

  private implicit val deviceIdUnmarshaller: akka.http.scaladsl.unmarshalling.Unmarshaller[
    String,
    com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
  ] = DeviceId.unmarshaller

  val route = extractNamespace { ns =>
    pathPrefix("assignments") {
      (path("devices") & parameter(Symbol("mtuId").as[UpdateId]) & parameter(
        Symbol("ids").as(CsvSeq[DeviceId])
      )) { (mtuId, deviceIds) =>
        val f = deviceAssignments.findAffectedDevices(ns, deviceIds, mtuId)
        complete(f)
      } ~
        pathEnd {
          post {
            entity(as[AssignUpdateRequest]) { req =>
              if (req.dryRun.contains(true)) { // Legacy API
                val f = deviceAssignments.findAffectedDevices(ns, req.devices, req.mtuId)
                complete(f)
              } else {
                val f: Future[ToResponseMarshallable] = createAssignments(ns, req).map {
                  case result if result.affected.nonEmpty    => StatusCodes.Created -> result
                  case result if result.notAffected.nonEmpty => StatusCodes.BadRequest -> result
                  case result                                => StatusCodes.OK -> result
                }

                complete(f)
              }
            }
          } ~
            patch {
              entity(as[Seq[DeviceId]]) { devices =>
                val a = deviceAssignments.cancel(ns, devices)
                complete(a.map(_.map(_.deviceId)))
              }
            } ~
            get {
              val deviceIdFetchLimit = 50
              parameter(Symbol("ids").as(CsvSeq[DeviceId])) { deviceIds =>
                val limit =
                  if (deviceIds.length > deviceIdFetchLimit) deviceIdFetchLimit
                  else deviceIds.length
                val f =
                  deviceAssignments.findMultiDeviceAssignments(ns, deviceIds.slice(0, limit).toSet)
                complete(f)
              }
            }
        } ~
        path(DeviceId.Path) { deviceId =>
          patch {
            optionalHeaderValueByType(ForceHeader) { force =>
              val f =
                deviceAssignments.cancel(ns, deviceId, cancelInFlight = force.exists(_.asBoolean))
              complete(f.map(_ => StatusCodes.NoContent))
            }
          } ~
            get { //  This should be replacing /queue in /admin
              val f = deviceAssignments.findDeviceAssignments(ns, deviceId)
              complete(f)
            }
        }
    }
  }

}
