package com.advancedtelematic.ota.deviceregistry.device_monitoring

import cats.syntax.either._
import org.scalatest.LoneElement._
import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId._
import com.advancedtelematic.ota.deviceregistry.data.DeviceGenerators
import com.advancedtelematic.ota.deviceregistry.{NooDeviceMonitoringResource, Resource, ResourceSpec}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import org.scalatest.EitherValues._
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import slick.jdbc.GetResult
import slick.jdbc.PostgresProfile.api._

class DeviceMonitoringResourceSpec extends FunSuite with ResourceSpec with ScalaFutures with DeviceGenerators {
  import com.advancedtelematic.ota.deviceregistry.data.GeneratorOps._

  override implicit def patienceConfig: PatienceConfig = super.patienceConfig.copy(timeout = Span(3, Seconds))

  val jsonPayload = io.circe.jawn.parse(
    """
      |{
      |    "cpu": {
      |        "cpu0.p_cpu": 0.19,
      |        "cpu0.p_system": 0.04666666666666667,
      |        "cpu0.p_user": 0.1433333333333333,
      |        "cpu1.p_cpu": 0.1933333333333333,
      |        "cpu1.p_system": 0.08333333333333333,
      |        "cpu1.p_user": 0.11,
      |        "cpu2.p_cpu": 0.2233333333333333,
      |        "cpu2.p_system": 0.06666666666666667,
      |        "cpu2.p_user": 0.1566666666666667,
      |        "cpu3.p_cpu": 0.22,
      |        "cpu3.p_system": 0.04666666666666667,
      |        "cpu3.p_user": 0.1733333333333333,
      |        "cpu4.p_cpu": 0.1333333333333333,
      |        "cpu4.p_system": 0.03666666666666667,
      |        "cpu4.p_user": 0.09666666666666666,
      |        "cpu5.p_cpu": 0.1533333333333333,
      |        "cpu5.p_system": 0.04666666666666667,
      |        "cpu5.p_user": 0.1066666666666667,
      |        "cpu_p": 0.1855555555555556,
      |        "system_p": 0.05444444444444444,
      |        "user_p": 0.1311111111111111
      |    },
      |    "date": 1621930398.017631,
      |    "docker": {
      |        "alive": true,
      |        "pid": 839,
      |        "proc_name": "dockerd"
      |    },
      |    "memory": {
      |        "Mem.free": 3105676,
      |        "Mem.total": 3797652,
      |        "Mem.used": 691976,
      |        "Swap.free": 0,
      |        "Swap.total": 0,
      |        "Swap.used": 0
      |    },
      |    "temperature": {
      |        "name": "thermal_zone0",
      |        "temp": 69.1,
      |        "type": "cpu-thermal0"
      |    }
      |}
      |""".stripMargin).right.value

  test("accepts metrics from device") {
    val uuid = createDeviceOk(genDeviceT.generate)

    Post(Resource.uri("devices", uuid.show, "monitoring"), jsonPayload) ~> route ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val sql = sql"""SELECT cpu_p, docker_alive from device_observations where device_uuid = ${uuid.show}""".as[(Double, Boolean)]

    monitoringDB.value.db.run(sql).futureValue.loneElement shouldBe (0.1855555555555556, true)

    implicit val getPayload = GetResult.apply { result =>
      io.circe.jawn.parse(result.rs.getString("payload")).valueOr(throw _)
    }

    val sql0 = sql"""SELECT payload from device_raw_observations where device_uuid = ${uuid.show}""".as[Json]

    monitoringDB.value.db.run(sql0).futureValue.loneElement shouldBe jsonPayload
  }

  test("responds with bad request if json is not a valid monitoring payload") {
    val uuid = createDeviceOk(genDeviceT.generate)

    Post(Resource.uri("devices", uuid.show, "monitoring"), Json.obj()) ~> route ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  test("noop routes return 204") {
    val uuid = createDeviceOk(genDeviceT.generate)

    val noOpRoutes = new NooDeviceMonitoringResource(namespaceAuthorizer).route

    Post(s"/devices/${uuid.show}/monitoring", jsonPayload) ~> noOpRoutes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val dropSql = sql"DROP SCHEMA IF EXISTS device_monitoring cascade; create schema device_monitoring".asUpdate
    monitoringDB.value.run(dropSql).futureValue
    monitoringDB.value.migrate().futureValue
  }
}
