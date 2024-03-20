package com.advancedtelematic.director.http.deviceregistry

import akka.http.scaladsl.model.StatusCodes.*
import com.advancedtelematic.director.daemon.DeleteDeviceRequestListener
import com.advancedtelematic.director.deviceregistry.daemon.{DeviceUpdateEventListener, EcuReplacementListener}
import com.advancedtelematic.director.deviceregistry.data.Codecs.installationStatDecoder
import com.advancedtelematic.director.deviceregistry.data.DataType.{InstallationStat, InstallationStatsLevel}
import com.advancedtelematic.director.deviceregistry.data.GeneratorOps.*
import com.advancedtelematic.director.deviceregistry.data.{DeviceStatus, InstallationReportGenerators}
import com.advancedtelematic.director.util.{DirectorSpec, ResourceSpec}
import com.advancedtelematic.libats.data.DataType.ResultCode
import com.advancedtelematic.libats.data.PaginationResult
import com.advancedtelematic.libats.messaging.test.MockMessageBus
import com.advancedtelematic.libats.messaging_datatype.MessageCodecs.{deviceUpdateCompletedCodec, ecuReplacementCodec}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeleteDeviceRequest, DeviceUpdateCompleted, EcuReplaced, EcuReplacement, EcuReplacementFailed}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import io.circe.Json
import org.scalacheck.Gen
import org.scalatest.EitherValues.*
import org.scalatest.LoneElement.*
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}

import java.time.Instant
import java.time.temporal.ChronoUnit

class InstallationReportSpec
    extends DirectorSpec
    with ResourcePropSpec
    with DeviceRequests
    with ScalaFutures
    with Eventually
    with InstallationReportGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(5, Seconds), Span(50, Millis))

  val updateListener = new DeviceUpdateEventListener(msgPub)
  val ecuReplacementListener = new EcuReplacementListener
  val deleteDeviceListener = new DeleteDeviceRequestListener()

  test("should save device reports and retrieve failed stats per devices") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3").map(ResultCode)
    val updatesCompleted =
      resultCodes.map(genDeviceUpdateCompleted(correlationId, _)).map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Device) ~> routes ~> check {
        status shouldBe OK
        val expected = Seq(
          InstallationStat(ResultCode("0"), 1, true),
          InstallationStat(ResultCode("1"), 1, false),
          InstallationStat(ResultCode("2"), 2, false),
          InstallationStat(ResultCode("3"), 3, false)
        )
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  test("should save device reports and retrieve failed stats per ECUs") {
    val correlationId = genCorrelationId.generate
    val resultCodes = Seq("0", "1", "2", "2", "3", "3", "3").map(ResultCode)
    val updatesCompleted =
      resultCodes.map(genDeviceUpdateCompleted(correlationId, _)).map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

    eventually {
      getStats(correlationId, InstallationStatsLevel.Ecu) ~> routes ~> check {
        status shouldBe OK
        val expected = Seq(
          InstallationStat(ResultCode("0"), 1, true),
          InstallationStat(ResultCode("1"), 1, false),
          InstallationStat(ResultCode("2"), 2, false),
          InstallationStat(ResultCode("3"), 3, false)
        )
        responseAs[Seq[InstallationStat]] shouldBe expected
      }
    }
  }

  test("should save the whole message as a blob and get back the history for a device") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val correlationIds = Gen.listOfN(50, genCorrelationId).generate
    val updatesCompleted = correlationIds
      .map(cid => genDeviceUpdateCompleted(cid, ResultCode("0"), deviceId))
      .map(_.generate)

    updatesCompleted.foreach(updateListener.apply)

    eventually {
      getReportBlob(deviceId) ~> routes ~> check {
        status shouldBe OK
        responseAs[
          PaginationResult[DeviceUpdateCompleted]
        ].values should contain allElementsOf updatesCompleted
      }
    }
  }

  test("does not overwrite existing reports") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val correlationId = genCorrelationId.generate
    val updateCompleted1 =
      genDeviceUpdateCompleted(correlationId, ResultCode("0"), deviceId).generate
    val updateCompleted2 =
      genDeviceUpdateCompleted(correlationId, ResultCode("1"), deviceId).generate

    updateListener.apply(updateCompleted1).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      responseAs[
        PaginationResult[DeviceUpdateCompleted]
      ].values.loneElement.result.code shouldBe ResultCode("0")
    }

    updateListener.apply(updateCompleted2).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      responseAs[
        PaginationResult[DeviceUpdateCompleted]
      ].values.loneElement.result.code shouldBe ResultCode("0")
    }
  }

  test("should fetch installation events and ECU replacement events") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)

    val correlationId1 = genCorrelationId.generate
    val correlationId2 = genCorrelationId.generate
    val updateCompleted1 =
      genDeviceUpdateCompleted(correlationId1, ResultCode("0"), deviceId, receivedAt = now).generate
    val successfulReplacement =
      genEcuReplacement(deviceId, now.plusSeconds(60), success = true).generate
    val updateCompleted2 = genDeviceUpdateCompleted(
      correlationId2,
      ResultCode("1"),
      deviceId,
      receivedAt = now.plusSeconds(120)
    ).generate
    val failedReplacement =
      genEcuReplacement(deviceId, now.plusSeconds(180), success = false).generate

    updateListener(updateCompleted1).futureValue
    ecuReplacementListener(successfulReplacement).futureValue
    updateListener.apply(updateCompleted2).futureValue
    ecuReplacementListener(failedReplacement).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]].values
      result(0)
        .as[EcuReplacement]
        .value
        .asInstanceOf[EcuReplacementFailed] shouldBe failedReplacement
      result(1).as[DeviceUpdateCompleted].value.result.code shouldBe ResultCode("1")
      result(2).as[EcuReplacement].value.asInstanceOf[EcuReplaced] shouldBe successfulReplacement
      result(3).as[DeviceUpdateCompleted].value.result.code shouldBe ResultCode("0")
    }
    fetchDeviceOk(deviceId).deviceStatus shouldBe DeviceStatus.Error
  }

  test(
    "fails gracefully if trying to record ECU replacements for a non-existent or deleted device"
  ) {
    val deviceId = createDeviceOk(genDeviceT.generate)

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      responseAs[PaginationResult[Json]].total shouldBe 0
    }

    deleteDeviceListener(DeleteDeviceRequest(defaultNs, deviceId)).futureValue

    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)
    val ecuReplaced = genEcuReplacement(deviceId, now, success = true).generate
    ecuReplacementListener(ecuReplaced).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  test("can delete replaced devices") {
    getReportBlob(genDeviceUUID.generate) ~> routes ~> check {
      status shouldBe NotFound
    }

    val deviceId = createDeviceOk(genDeviceT.generate)

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]]
      result.total shouldBe 0
    }

    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)
    val ecuReplaced = genEcuReplacement(deviceId, now, success = true).generate
    ecuReplacementListener(ecuReplaced).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Json]].values
      result.head.as[EcuReplacement].value.asInstanceOf[EcuReplaced] shouldBe ecuReplaced
    }

    val deleteDeviceRequest = DeleteDeviceRequest(defaultNs, deviceId)
    deleteDeviceListener(deleteDeviceRequest).futureValue

    getReportBlob(deviceId) ~> routes ~> check {
      status shouldBe NotFound
    }
  }

  test("multiple ECU replacement error is handled gracefully") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)
    val ecuReplaced = genEcuReplacement(deviceId, now, success = true).generate
    ecuReplacementListener(ecuReplaced).futureValue
    ecuReplacementListener(ecuReplaced).futureValue
  }

  test("empty installation reports") {
    val deviceId = createDeviceOk(genDeviceT.generate)

    getInstallationReports(deviceId) ~> routes ~> check {
      status shouldBe OK
      responseAs[PaginationResult[DeviceUpdateCompleted]].total shouldBe 0
    }
  }

  test("one installationReport") {
    val deviceId = createDeviceOk(genDeviceT.generate)
    val now = Instant.now.truncatedTo(ChronoUnit.SECONDS)

    val correlationId = genCorrelationId.generate
    val updateCompleted = genDeviceUpdateCompleted(
      correlationId,
      ResultCode("0"),
      deviceId,
      receivedAt = now.plusSeconds(10)
    ).generate

    updateListener(updateCompleted).futureValue
    getInstallationReports(deviceId) ~> routes ~> check {
      status shouldBe OK
      responseAs[
        PaginationResult[DeviceUpdateCompleted]
      ].values.loneElement.result.code shouldBe ResultCode("0")
    }
  }

}
