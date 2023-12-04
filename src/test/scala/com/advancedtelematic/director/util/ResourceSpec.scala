package com.advancedtelematic.director.util

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.advancedtelematic.director.client.FakeKeyserverClient
import com.advancedtelematic.director.http.DirectorRoutes
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.test.MysqlDatabaseSpec
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.TufDataType.{SignedPayload, TufKeyPair}
import io.circe.Encoder
import org.scalatest.Suite
import com.advancedtelematic.director.Settings
import com.advancedtelematic.director.data.AdminDataType.TargetUpdate
import com.advancedtelematic.director.data.UptaneDataType.*
import com.advancedtelematic.director.data.DbDataType.Ecu
import com.advancedtelematic.director.data.DeviceRequest
import com.advancedtelematic.director.data.DeviceRequest.{DeviceManifest, EcuManifest, InstallationReport, InstallationReportEntity, MissingInstallationReport}
import com.advancedtelematic.libats.data.EcuIdentifier
import com.advancedtelematic.director.data.Codecs.*
import com.advancedtelematic.director.data.UptaneDataType.Image
import com.advancedtelematic.libats.messaging.test.MockMessageBus

import scala.concurrent.ExecutionContextExecutor

trait ResourceSpec extends ScalatestRouteTest with MysqlDatabaseSpec with Settings {
  self: Suite =>

  def apiUri(path: String): String = "/api/v1/" + path

  val defaultNs = Namespace("default")

  implicit val msgPub: MockMessageBus = new MockMessageBus

  implicit val ec: ExecutionContextExecutor = executor
}

trait RouteResourceSpec extends ResourceSpec {
  self: Suite =>

  val keyserverClient = new FakeKeyserverClient

  lazy val routes: Route = new DirectorRoutes(keyserverClient, allowEcuReplacement = true).routes
}

trait DeviceManifestSpec {
  import io.circe.syntax._

  def sign[T : Encoder](key: TufKeyPair, payload: T): SignedPayload[T] = {
    val signature = TufCrypto.signPayload(key.privkey, payload.asJson).toClient(key.pubkey.id)
    SignedPayload(List(signature), payload, payload.asJson)
  }

  def buildEcuManifest(ecuSerial: EcuIdentifier, targetUpdate: TargetUpdate): EcuManifest = {
    val image = Image(targetUpdate.target, FileInfo(Hashes(targetUpdate.checksum), targetUpdate.targetLength))
    EcuManifest(image, ecuSerial, "", custom = None)
  }

  def buildPrimaryManifest(primary: Ecu, ecuKey: TufKeyPair, targetUpdate: TargetUpdate, reportO: Option[InstallationReport] = None): SignedPayload[DeviceManifest] = {
    val ecuManifest = sign(ecuKey, buildEcuManifest(primary.ecuSerial, targetUpdate))
    val report = reportO.map { r => InstallationReportEntity("mock-content-type", r) }.toRight(MissingInstallationReport)
    sign(ecuKey, DeviceRequest.DeviceManifest(primary.ecuSerial, Map(primary.ecuSerial -> ecuManifest), installation_report = report))
  }

  def buildSecondaryManifest(primary: EcuIdentifier, ecuKey: TufKeyPair, secondary: EcuIdentifier, secondaryKey: TufKeyPair, updates: Map[EcuIdentifier, TargetUpdate]): SignedPayload[DeviceManifest] = {
    val secondaryManifest = sign(secondaryKey, buildEcuManifest(secondary, updates(secondary)))
    val primaryManifest = sign(ecuKey, buildEcuManifest(primary, updates(primary)))
    val m = Map(primary -> primaryManifest, secondary-> secondaryManifest)
    sign(ecuKey, DeviceManifest(primary, m, installation_report = Left(MissingInstallationReport)))
  }
}
