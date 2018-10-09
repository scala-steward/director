/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import java.util.Base64

import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import com.advancedtelematic.ota.deviceregistry.data.CredentialsType.CredentialsType
import com.advancedtelematic.ota.deviceregistry.PublicCredentialsResource.FetchPublicCredentials
import eu.timepit.refined.api.Refined

import scala.concurrent.ExecutionContext
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId => DeviceUUID}
import com.advancedtelematic.ota.deviceregistry.data.Codecs._
import com.advancedtelematic.ota.deviceregistry.data.DataType.DeviceT

trait PublicCredentialsRequests { self: ResourceSpec =>
  import StatusCodes._
  import com.advancedtelematic.ota.deviceregistry.data.Device._
  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

  private val credentialsApi = "devices"

  private lazy val base64Decoder = Base64.getDecoder()
  private lazy val base64Encoder = Base64.getEncoder()

  def fetchPublicCredentials(device: DeviceUUID): HttpRequest = {
    import cats.syntax.show._
    Get(Resource.uri(credentialsApi, device.show, "public_credentials"))
  }

  def fetchPublicCredentialsOk(device: DeviceUUID): Array[Byte] =
    fetchPublicCredentials(device) ~> route ~> check {
      implicit val CredentialsDecoder = io.circe.generic.semiauto.deriveDecoder[FetchPublicCredentials]
      status shouldBe OK
      val resp = responseAs[FetchPublicCredentials]
      base64Decoder.decode(resp.credentials)
    }

  def createDeviceWithCredentials(devT: DeviceT)(implicit ec: ExecutionContext): HttpRequest =
    Put(Resource.uri(credentialsApi), devT)

  def updatePublicCredentials(device: DeviceOemId, creds: Array[Byte], cType: Option[CredentialsType])(
      implicit ec: ExecutionContext
  ): HttpRequest = {
    val devT = DeviceT(
      Refined.unsafeApply(device.underlying),
      None,
      Some(device),
      credentials = Some(base64Encoder.encodeToString(creds)),
      credentialsType = cType)
    createDeviceWithCredentials(devT)
  }

  def updatePublicCredentialsOk(
                                 device: DeviceOemId,
                                 creds: Array[Byte],
                                 cType: Option[CredentialsType] = None
  )(implicit ec: ExecutionContext): DeviceUUID =
    updatePublicCredentials(device, creds, cType) ~> route ~> check {
      val uuid = responseAs[DeviceUUID]
      status shouldBe OK
      uuid
    }
}
