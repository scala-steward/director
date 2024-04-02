package com.advancedtelematic.director.http.deviceregistry

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.Uri.Query
import cats.syntax.option.*
import com.advancedtelematic.director.data.Generators.GenHardwareIdentifier
import com.advancedtelematic.director.deviceregistry.data.*
import com.advancedtelematic.director.deviceregistry.data.Codecs.*
import com.advancedtelematic.director.deviceregistry.data.DataType.DevicesQuery
import com.advancedtelematic.director.deviceregistry.data.Device.DeviceOemId
import com.advancedtelematic.director.deviceregistry.data.DeviceGenerators.*
import com.advancedtelematic.director.deviceregistry.data.DeviceSortBy.DeviceSortBy
import com.advancedtelematic.director.deviceregistry.data.GeneratorOps.*
import com.advancedtelematic.director.deviceregistry.data.Namespaces.*
import com.advancedtelematic.director.deviceregistry.data.SortDirection.SortDirection
import com.advancedtelematic.director.http.{AdminResources, ProvisionedDevicesRequests}
import com.advancedtelematic.director.http.deviceregistry.Errors.Codes
import com.advancedtelematic.director.util.{DirectorSpec, RepositorySpec, ResourceSpec}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.data.{ErrorCodes, ErrorRepresentation, PaginationResult}
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import io.circe.Json

import java.time.{Duration, Instant}

trait SearchRequests {
  self: DirectorSpec & ResourceSpec & DeviceRequests =>

  def listDevices(sortBy: Option[DeviceSortBy] = None,
                  sortDirection: Option[SortDirection] = None): HttpRequest = {
    val m = (sortBy, sortDirection) match {
      case (None, _)          => Map.empty[String, String]
      case (Some(sort), None) => Map("sortBy" -> sort.toString)
      case (Some(sort), Some(sortDir)) =>
        Map("sortBy" -> sort.toString, "sortDirection" -> sortDir.toString)
    }
    Get(DeviceRegistryResourceUri.uri(api).withQuery(Query(m)))
  }

  def listDevicesByUuids(deviceUuids: Seq[DeviceId],
                         sortBy: Option[DeviceSortBy] = None): HttpRequest = {
    val m = sortBy.fold(Map.empty[String, String])(s => Map("sortBy" -> s.toString))
    Get(
      DeviceRegistryResourceUri.uri(api).withQuery(Query(m)),
      DevicesQuery(None, Some(deviceUuids.toList))
    )
  }

  def searchDevice(regex: String, offset: Long = 0, limit: Long = 50): HttpRequest =
    Get(
      DeviceRegistryResourceUri
        .uri(api)
        .withQuery(Query("regex" -> regex, "offset" -> offset.toString, "limit" -> limit.toString))
    )

}

class SearchResourceSpec
    extends DirectorSpec
    with ResourceSpec
    with DeviceRequests
    with SearchRequests
    with AdminResources
    with RepositorySpec
    with ProvisionedDevicesRequests {

  test(
    "querying devices with duplicate devices specified are not duplicated in response (response should be a set)"
  ) {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      val deviceOemIds = devicesResponse.map(_.deviceId)
      val deviceUuids = devicesResponse.map(_.uuid)
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(Some(deviceOemIds), Some(deviceUuids))
      ) ~> routes ~> check {
        status shouldBe OK
        val responseDevices = responseAs[List[Device]]
        responseDevices.length shouldBe devices.length
        responseDevices should contain theSameElementsAs devicesResponse
        responseDevices.map(_.deviceId) should contain theSameElementsAs deviceOemIds
      }
    }
  }

  testWithRepo("can search devices by hardware id") { implicit ns =>
    val device1 = genDeviceT.sample.get
    val device2 = genDeviceT.sample.get
    val uuid1 = createDeviceInNamespaceOk(device1, ns)
    val uuid2 = createDeviceInNamespaceOk(device2, ns)

    val hwId = GenHardwareIdentifier.generate

    registerAdminDeviceOk(hwId.some, uuid1)
    registerAdminDeviceOk(deviceId = uuid2)

    filterDevices(namespace = ns, hardwareIds = Seq(hwId)) ~> routes ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]].values.map(_.uuid)
      result.length shouldBe 1
      result shouldBe Seq(uuid1)
    }
  }

  test("querying devices with bad DeviceOemId fails gracefully") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      val deviceOemIds = devicesResponse.map(_.deviceId) :+ DeviceOemId("not-real-deviceId")
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(Some(deviceOemIds), None)
      ) ~> routes ~> check {
        status shouldBe NotFound
        val errResponse = responseAs[ErrorRepresentation]
        errResponse.code shouldBe Codes.MissingDevice
        errResponse.cause shouldBe defined
        val errMap = errResponse.cause
          .getOrElse(Json.fromString("{}"))
          .as[Map[String, String]]
          .getOrElse(Map.empty[String, String])
        errMap.keys should contain("missingDeviceUuids")
        errMap.keys should contain("missingOemIds")
        errMap("missingOemIds") should not be empty
        errMap("missingDeviceUuids") shouldBe empty
      }
    }
  }

  test("querying devices with bad DeviceUuid fails gracefully") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      val deviceUuids = devicesResponse.map(_.uuid) :+ DeviceId.generate()
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(None, Some(deviceUuids))
      ) ~> routes ~> check {
        status shouldBe NotFound
        val errResponse = responseAs[ErrorRepresentation]
        errResponse.code shouldBe Codes.MissingDevice
        errResponse.cause shouldBe defined
        val errMap = errResponse.cause
          .getOrElse(Json.fromString("{}"))
          .as[Map[String, String]]
          .getOrElse(Map.empty[String, String])
        errMap.keys should contain("missingDeviceUuids")
        errMap.keys should contain("missingOemIds")
        errMap("missingOemIds") shouldBe empty
        errMap("missingDeviceUuids") should not be empty
      }
    }
  }

  test("querying devices with bad DeviceOemIds and DeviceUuids fails gracefully") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      val deviceUuids = devicesResponse.map(_.uuid) :+ DeviceId.generate()
      val deviceOemIds = devicesResponse.map(_.deviceId) :+ DeviceOemId("not-real-deviceId")
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(Some(deviceOemIds), Some(deviceUuids))
      ) ~> routes ~> check {
        status shouldBe NotFound
        val errResponse = responseAs[ErrorRepresentation]
        errResponse.code shouldBe Codes.MissingDevice
        errResponse.cause shouldBe defined

        val errMap = errResponse.cause
          .getOrElse(Json.fromString("{}"))
          .as[Map[String, String]]
          .getOrElse(Map.empty[String, String])
        errMap.keys should contain("missingDeviceUuids")
        errMap.keys should contain("missingOemIds")
        errMap("missingOemIds") should not be empty
        errMap("missingDeviceUuids") should not be empty
      }
    }
  }

  test("fetches only devices for the given namespace") {
    val dt1 = genDeviceT.generate
    val dt2 = genDeviceT.generate

    val d1 = createDeviceInNamespaceOk(dt1, defaultNs)
    val d2 = createDeviceInNamespaceOk(dt2, Namespace("test-namespace"))

    listDevices() ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values.map(_.uuid)
      devices should contain(d1)
      devices should not contain d2
    }
  }

  test("can list devices with custom pagination limit") {
    val limit = 30
    val deviceTs = genConflictFreeDeviceTs(limit * 2).sample.get
    deviceTs.foreach(createDeviceOk)

    searchDevice("", limit = limit) ~> routes ~> check {
      status shouldBe OK
      val result = responseAs[PaginationResult[Device]]
      result.values.length shouldBe limit
    }
  }

  test("can list devices with custom pagination limit and offset") {
    val limit = 30
    val offset = 10
    val deviceTs = genConflictFreeDeviceTs(limit * 2).sample.get
    deviceTs.foreach(createDeviceOk)

    searchDevice("", offset = offset, limit = limit) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.length shouldBe limit
      devices.zip(devices.tail).foreach { case (device1, device2) =>
        device1.deviceName.value.compareTo(device2.deviceName.value) should be <= 0
      }
    }
  }

  test("list devices with negative pagination limit fails") {
    searchDevice("", limit = -1) ~> routes ~> check {
      status shouldBe BadRequest
      val res = responseAs[ErrorRepresentation]
      res.code shouldBe ErrorCodes.InvalidEntity
      res.description should include("The query parameter 'limit' was malformed")
    }
  }

  test("list devices with negative pagination offset fails") {
    searchDevice("", offset = -1) ~> routes ~> check {
      status shouldBe BadRequest
      val res = responseAs[ErrorRepresentation]
      res.code shouldBe ErrorCodes.InvalidEntity
      res.description should include("The query parameter 'offset' was malformed")
    }
  }

  test("finds all the devices sorted by name ascending") {
    listDevices(Some(DeviceSortBy.Name)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.deviceName.value)
    }
  }

  test("finds all the devices sorted by name descending") {
    listDevices(Some(DeviceSortBy.Name), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.deviceName.value).reverse
    }
  }

  test("finds all the devices sorted by DeviceId ascending") {
    listDevices(Some(DeviceSortBy.DeviceId)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.deviceId.underlying)
    }
  }

  test("finds all the devices sorted by DeviceId descending") {
    listDevices(Some(DeviceSortBy.DeviceId), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.deviceId.underlying).reverse
    }
  }

  test("finds all the devices sorted by Uuid ascending") {
    listDevices(Some(DeviceSortBy.Uuid)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.uuid.uuid.toString)
    }
  }

  test("finds all the devices sorted by Uuid descending") {
    listDevices(Some(DeviceSortBy.Uuid), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.uuid.uuid.toString).reverse
    }
  }

  test("finds all the devices sorted by creation date ascending") {
    listDevices(Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.map(_.createdAt) shouldBe devices.sortBy(_.createdAt).map(_.createdAt)
    }
  }

  test("finds all the devices sorted by creation date descending") {
    listDevices(Some(DeviceSortBy.CreatedAt), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.map(_.createdAt) shouldBe devices.sortBy(_.createdAt).map(_.createdAt).reverse
    }
  }

  test("finds all the devices sorted by Activation Date ascending") {
    listDevices(Some(DeviceSortBy.ActivatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.activatedAt)
    }
  }

  test("finds all the devices sorted by Activation Date descending") {
    listDevices(Some(DeviceSortBy.ActivatedAt), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.map(_.activatedAt) shouldBe devices.sortBy(_.activatedAt).reverse.map(_.activatedAt)
    }
  }

  test("finds all the devices sorted by LastSeen ascending") {
    listDevices(Some(DeviceSortBy.LastSeen)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices shouldBe devices.sortBy(_.lastSeen)
    }
  }

  test("finds all the devices sorted by LastSeen descending") {
    listDevices(Some(DeviceSortBy.LastSeen), Some(SortDirection.Desc)) ~> routes ~> check {
      status shouldBe OK
      val devices = responseAs[PaginationResult[Device]].values
      devices.map(_.lastSeen) shouldBe devices.sortBy(_.lastSeen).reverse.map(_.lastSeen)
    }
  }

  test("can fetch devices by UUIDs") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids
    }
  }

  test("can fetch devices by UUIDs and sorted") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids
    }
  }

  test("can query devices using DeviceOemIds") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      // so now try to do this with deviceOemIds
      val deviceOemIds = devicesResponse.map(_.deviceId)
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(Some(deviceOemIds), None)
      ) ~> routes ~> check {
        status shouldBe OK
        val responseDevices = responseAs[List[Device]]
        responseDevices.length shouldBe devices.length
        responseDevices.map(_.deviceId) should contain theSameElementsAs deviceOemIds
      }
    }
  }

  test("can query devices with DeviceOemIds AND DeviceUuids") {
    val devices = genConflictFreeDeviceTs(10).generate
    val uuids = devices.map(createDeviceOk)

    listDevicesByUuids(uuids, Some(DeviceSortBy.CreatedAt)) ~> routes ~> check {
      status shouldBe OK
      val devicesResponse = responseAs[List[Device]]
      devicesResponse.length shouldBe devices.length
      devicesResponse.map(_.uuid) should contain theSameElementsAs uuids

      // query the first 5 as deviceOemIds, and the last 5 as deviceUuids
      val deviceOemIds = devicesResponse.slice(0, 5).map(_.deviceId)
      val deviceUuids = devicesResponse.slice(5, devicesResponse.length).map(_.uuid)
      Get(
        DeviceRegistryResourceUri.uri("devices"),
        DevicesQuery(Some(deviceOemIds), Some(deviceUuids))
      ) ~> routes ~> check {
        status shouldBe OK
        val responseDevices = responseAs[List[Device]]
        responseDevices.length shouldBe devices.length
        responseDevices should contain theSameElementsAs devicesResponse
      }
    }
  }

}
