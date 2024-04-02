package com.advancedtelematic.director.http

import org.scalatest.LoneElement.*
import akka.http.scaladsl.model.StatusCodes
import cats.syntax.option.*
import cats.syntax.show.*
import com.advancedtelematic.director.daemon.UpdateScheduler
import com.advancedtelematic.director.data.AdminDataType.*
import com.advancedtelematic.director.data.Codecs.*
import com.advancedtelematic.director.data.DataType.TargetItemCustom
import com.advancedtelematic.director.data.GeneratorOps.*
import com.advancedtelematic.director.data.Generators.*
import com.advancedtelematic.director.db.{
  DbDeviceRoleRepositorySupport,
  RepoNamespaceRepositorySupport
}
import com.advancedtelematic.director.http.DeviceAssignments.AssignmentCreateResult
import com.advancedtelematic.director.util.*
import com.advancedtelematic.libats.data.DataType.{CorrelationId, MultiTargetUpdateId, Namespace}
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libats.messaging.test.MockMessageBus
import com.advancedtelematic.libats.messaging_datatype.DataType.{DeviceId, UpdateId}
import com.advancedtelematic.libats.messaging_datatype.Messages.{DeviceUpdateEvent, *}
import com.advancedtelematic.libtuf.data.ClientCodecs.*
import com.advancedtelematic.libtuf.data.ClientDataType.TargetsRole
import com.advancedtelematic.libtuf.data.TufCodecs.*
import com.advancedtelematic.libtuf.data.TufDataType.{HardwareIdentifier, SignedPayload}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport.*
import org.scalactic.source.Position
import org.scalatest.Inspectors
import org.scalatest.OptionValues.*

import java.time.Instant
import scala.annotation.unused

trait AssignmentResources {
  self: DirectorSpec & ResourceSpec & NamespacedTests & AdminResources =>

  def createDeviceAssignment(deviceId: DeviceId,
                             hwId: HardwareIdentifier,
                             targetUpdateO: Option[TargetUpdateRequest] = None,
                             correlationIdO: Option[CorrelationId] = None)(
    checkV: => Any)(implicit ns: Namespace, pos: Position): AssignUpdateRequest =
    createAssignment(Seq(deviceId), hwId, targetUpdateO, correlationIdO)(checkV)

  def createAssignment(deviceIds: Seq[DeviceId],
                       hwId: HardwareIdentifier,
                       targetUpdateO: Option[TargetUpdateRequest] = None,
                       correlationIdO: Option[CorrelationId] = None)(
    checkV: => Any)(implicit ns: Namespace, pos: Position): AssignUpdateRequest = {
    val correlationId = correlationIdO.getOrElse(GenCorrelationId.generate)

    val targetUpdate = targetUpdateO.getOrElse(GenTargetUpdateRequest.generate)
    val mtu = MultiTargetUpdate(Map(hwId -> targetUpdate))

    val mtuId = Post(apiUri("multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

    val assignment = AssignUpdateRequest(correlationId, deviceIds, mtuId)

    Post(apiUri("assignments"), assignment).namespaced ~> routes ~> check(checkV)

    assignment
  }

  def createAssignmentOk(deviceIds: Seq[DeviceId],
                         hwId: HardwareIdentifier,
                         targetUpdateO: Option[TargetUpdateRequest] = None,
                         correlationIdO: Option[CorrelationId] = None)(
    implicit ns: Namespace,
    pos: Position): AssignUpdateRequest =
    createAssignment(deviceIds, hwId, targetUpdateO, correlationIdO) {
      status shouldBe StatusCodes.Created
    }

  def createDeviceAssignmentOk(deviceId: DeviceId,
                               hwId: HardwareIdentifier,
                               targetUpdateO: Option[TargetUpdateRequest] = None,
                               correlationIdO: Option[CorrelationId] = None)(
    implicit ns: Namespace,
    pos: Position): AssignUpdateRequest =
    createAssignmentOk(Seq(deviceId), hwId, targetUpdateO, correlationIdO)

  def getDeviceAssignment[T](deviceId: DeviceId)(checkFn: => T)(implicit ns: Namespace): T =
    Get(apiUri(s"assignments/${deviceId.show}")).namespaced ~> routes ~> check(checkFn)

  def getDeviceAssignmentOk(
    deviceId: DeviceId)(implicit ns: Namespace, pos: Position): Seq[QueueResponse] =
    getDeviceAssignment(deviceId) {
      status shouldBe StatusCodes.OK
      responseAs[Seq[QueueResponse]]
    }

  def getMultipleDeviceAssignments[T](devices: Set[DeviceId])(
    checkFn: => T)(implicit ns: Namespace, @unused pos: Position): T =
    Get(
      apiUri(
        // make a quick csv query
        s"assignments?ids=${devices.map(_.uuid.show).mkString(",")}"
      )
    ).namespaced ~> routes ~> check(checkFn)

  def getTargetsOk(deviceId: DeviceId)(implicit ns: Namespace): SignedPayload[TargetsRole] =
    Get(apiUri(s"device/${deviceId.show}/targets.json")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]]
    }

  def cancelAssignmentsOk(deviceIds: Seq[DeviceId])(implicit ns: Namespace): Seq[DeviceId] =
    Patch(apiUri("assignments"), deviceIds).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]]
    }

}

class AssignmentsResourceSpec
    extends DirectorSpec
    with ResourceSpec
    with RepoNamespaceRepositorySupport
    with DbDeviceRoleRepositorySupport
    with AdminResources
    with AssignmentResources
    with RepositorySpec
    with ProvisionedDevicesRequests
    with DeviceManifestSpec
    with Inspectors {

  override implicit val msgPub = new MockMessageBus

  val updateScheduler = new UpdateScheduler()

  testWithRepo("Can create an assignment for existing devices") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)
  }

  testWithRepo("GET queue for affected devices includes newly created assignment") { implicit ns =>
    val now = Instant.now.minusSeconds(1)
    val regDev = registerAdminDeviceOk()
    val assignment = createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val queue = getDeviceAssignmentOk(assignment.devices.head)
    queue.map(_.correlationId) should contain(assignment.correlationId)

    forAll(queue.flatMap(_.targets.values.map(_.createdAt))) { createdAt =>
      createdAt.isAfter(now) shouldBe true
    }
  }

  testWithRepo(
    "returns PrimaryIsNotListedForDevice when ecus to register do not include primary ecu"
  ) { implicit ns =>
    val device = DeviceId.generate()
    val (regEcu, _) = GenRegisterEcuKeys.generate
    val ecu = GenEcuIdentifier.generate
    val regDev = RegisterDevice(device.some, ecu, List(regEcu))

    Post(apiUri("admin/devices"), regDev).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest

      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PrimaryIsNotListedForDevice
    }
  }

  testWithRepo("can GET devices affected by assignment") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk()

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdate(Map(regDev0.primary.hardwareId -> targetUpdate))

    val mtuId = Post(apiUri("multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

    Get(
      apiUri(
        s"assignments/devices?mtuId=${mtuId.show}&ids=${regDev0.deviceId.show},${regDev1.deviceId.show}"
      )
    ).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]] should contain(regDev0.deviceId)
      responseAs[Seq[DeviceId]] shouldNot contain(regDev1.deviceId)
    }
  }

  testWithRepo("can GET devices affected by assignment using legacy API") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk()

    val targetUpdate = GenTargetUpdateRequest.generate
    val mtu = MultiTargetUpdate(Map(regDev0.primary.hardwareId -> targetUpdate))

    val mtuId = Post(apiUri("multi_target_updates"), mtu).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }

    val assignment = AssignUpdateRequest(
      MultiTargetUpdateId(mtuId.uuid),
      Seq(regDev0.deviceId, regDev1.deviceId),
      mtuId,
      dryRun = Some(true)
    )

    Post(apiUri("assignments"), assignment).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[Seq[DeviceId]] should contain(regDev0.deviceId)
      responseAs[Seq[DeviceId]] shouldNot contain(regDev1.deviceId)
    }
  }

  testWithRepo("Only creates assignments for affected devices") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk()

    createDeviceAssignmentOk(regDev0.deviceId, regDev0.primary.hardwareId)

    val queue0 = getDeviceAssignmentOk(regDev0.deviceId)
    queue0 shouldNot be(empty)

    val queue1 = getDeviceAssignmentOk(regDev1.deviceId)
    queue1 shouldBe empty
  }

  testWithRepo("when requested, returns detailed results of affected devices") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk(regDev0.primary.hardwareId.some)

    val targetUpdate = GenTargetUpdateRequest.generate
    putManifestOk(
      regDev0.deviceId,
      buildPrimaryManifest(regDev0.primary, regDev0.primaryKey, targetUpdate.to)
    )

    val otherUpdate = GenTargetUpdate.generate
    putManifestOk(
      regDev1.deviceId,
      buildPrimaryManifest(regDev1.primary, regDev1.primaryKey, otherUpdate)
    )

    createAssignment(
      List(regDev0.deviceId, regDev1.deviceId),
      regDev0.primary.hardwareId,
      targetUpdate.some
    ) {
      status shouldBe StatusCodes.Created

      val response = responseAs[AssignmentCreateResult]

      response.affected.loneElement shouldBe regDev1.deviceId

      val (deviceId, errors) = response.notAffected.loneElement
      val (ecuId, error) = errors.loneElement
      deviceId shouldBe regDev0.deviceId
      ecuId shouldBe regDev0.primary.ecuSerial
      error.code shouldBe ErrorCodes.InstalledTargetIsUpdate
    }
  }

  testWithRepo("ecus are not affected if they already have target installed") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk(regDev0.primary.hardwareId.some)

    val targetUpdate = GenTargetUpdateRequest.generate
    putManifestOk(
      regDev0.deviceId,
      buildPrimaryManifest(regDev0.primary, regDev0.primaryKey, targetUpdate.to)
    )

    val otherUpdate = GenTargetUpdate.generate
    putManifestOk(
      regDev1.deviceId,
      buildPrimaryManifest(regDev1.primary, regDev1.primaryKey, otherUpdate)
    )

    createAssignmentOk(
      List(regDev0.deviceId, regDev1.deviceId),
      regDev0.primary.hardwareId,
      targetUpdate.some
    )

    val queue0 = getDeviceAssignmentOk(regDev0.deviceId)
    queue0 should be(empty)

    val queue1 = getDeviceAssignmentOk(regDev1.deviceId)
    queue1 shouldNot be(empty)
  }

  testWithRepo("ecus are not affected if the device has scheduled updates") { implicit ns =>
    val regDev0 = registerAdminDeviceOk()
    val regDev1 = registerAdminDeviceOk(regDev0.primary.hardwareId.some)

    val mtu = MultiTargetUpdate(Map(regDev0.primary.hardwareId -> GenTargetUpdateRequest.generate))
    val scheduledUpdate = createMtu(mtu) ~> check {
      response.status shouldBe StatusCodes.Created
      responseAs[UpdateId]
    }
    updateScheduler.create(ns, regDev0.deviceId, scheduledUpdate, Instant.now).futureValue

    val existingUpdate = GenTargetUpdate.generate
    putManifestOk(
      regDev1.deviceId,
      buildPrimaryManifest(regDev1.primary, regDev1.primaryKey, existingUpdate)
    )

    val targetUpdate = GenTargetUpdateRequest.generate
    createAssignment(
      List(regDev0.deviceId, regDev1.deviceId),
      regDev0.primary.hardwareId,
      targetUpdate.some
    ) {
      status shouldBe StatusCodes.Created

      val response = responseAs[AssignmentCreateResult]

      response.affected.loneElement shouldBe regDev1.deviceId

      val (deviceId, errors) = response.notAffected.loneElement
      val (ecuId, error) = errors.loneElement
      deviceId shouldBe regDev0.deviceId
      error.code shouldBe ErrorCodes.DeviceHasScheduledUpdate
    }

    val queue0 = getDeviceAssignmentOk(regDev0.deviceId)
    queue0 should be(empty)

    val queue1 = getDeviceAssignmentOk(regDev1.deviceId)
    queue1 shouldNot be(empty)
  }

  testWithRepo("Only creates assignments for affected ecus in a device") { implicit ns =>
    val regDev = registerAdminDeviceWithSecondariesOk()

    createDeviceAssignmentOk(regDev.deviceId, regDev.secondaries.values.head.hardwareId)

    val queue = getDeviceAssignmentOk(regDev.deviceId)

    queue.head.targets.get(regDev.primary.ecuSerial) should be(empty)
    queue.head.targets.get(regDev.secondaries.keys.head) should be(defined)
  }

  // TODO: director should return 4xx and campaigner should handle that
  // https://saeljira.it.here.com/browse/OTA-4956
  testWithRepo("returns ok if no devices are affected by assignment") { implicit ns =>
    createAssignment(Seq.empty, GenHardwareIdentifier.generate) {
      status shouldBe StatusCodes.OK
      responseAs[AssignmentCreateResult].affected shouldBe empty
      responseAs[AssignmentCreateResult].notAffected shouldBe empty
    }
  }

  testWithRepo("returns 4xx if device doesn't have correct hardware id") { implicit ns =>
    val regDev = registerAdminDeviceOk()

    createDeviceAssignment(regDev.deviceId, GenHardwareIdentifier.generate) {
      status shouldBe StatusCodes.BadRequest
      val notAffectedErrors = responseAs[AssignmentCreateResult].notAffected(regDev.deviceId)
      val error = notAffectedErrors.get(regDev.primary.ecuSerial).value
      error.code shouldBe ErrorCodes.DeviceNoCompatibleHardware
    }
  }

  // TODO: Same test as above, should pass once OTA-4956 is implemented
  testWithRepo("fails if no ecus are affected by assignment") { implicit ns =>
    pending
    val regDev = registerAdminDeviceOk()

    createDeviceAssignment(regDev.deviceId, GenHardwareIdentifier.generate) {
      status shouldBe StatusCodes.BadRequest
    }
  }

  testWithRepo(
    "create assignment returns 4xx if there is a created assignment for an ecu already, but device should not be affected"
  ) { implicit ns =>
    val regDev = registerAdminDeviceOk()

    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    createDeviceAssignment(regDev.deviceId, regDev.primary.hardwareId) {
      status shouldBe StatusCodes.BadRequest

      val response = responseAs[AssignmentCreateResult]

      response.affected shouldBe empty

      val (deviceId, errors) = response.notAffected.loneElement
      val (ecuId, error) = errors.loneElement
      deviceId shouldBe regDev.deviceId
      ecuId shouldBe regDev.primary.ecuSerial
      error.code shouldBe ErrorCodes.NotAffectedRunningAssignment
    }
  }

  testWithRepo("PATCH assignments cancels assigned updates") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq(regDev.deviceId)

    val queue = getDeviceAssignmentOk(regDev.deviceId)
    queue shouldBe empty

    val msg = msgPub.findReceived[DeviceUpdateEvent] { (msg: DeviceUpdateEvent) =>
      msg.deviceUuid == regDev.deviceId
    }

    msg shouldBe defined
    msg.get shouldBe a[DeviceUpdateCanceled]
  }

  testWithRepo("PATCH assignments can only cancel if update is not in-flight") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    // make it inflight
    getTargetsOk(regDev.deviceId)

    cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq.empty
  }

  testWithRepo("PATCH on assignments/device-id cancels asssignmnet") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    Patch(apiUri(s"assignments/${regDev.deviceId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val targets = getTargetsOk(regDev.deviceId)
    targets.signed.targets shouldBe empty
  }

  testWithRepo(
    "PATCH on assignments/device-id cancels assignment if in flight and force header is used"
  ) { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val targets0 = getTargetsOk(regDev.deviceId)
    targets0.signed.targets shouldNot be(empty)

    Patch(apiUri(s"assignments/${regDev.deviceId.show}"))
      .addHeader(new ForceHeader(true))
      .namespaced ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }

    val targets = getTargetsOk(regDev.deviceId)
    targets.signed.targets shouldBe empty
  }

  testWithRepo(
    "PATCH on assignments/device-id does not cancel assignment if in flight and force header is not used"
  ) { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    getTargetsOk(regDev.deviceId)

    Patch(apiUri(s"assignments/${regDev.deviceId.show}")).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Conflict
    }

    val targets = getTargetsOk(regDev.deviceId)
    targets.signed.targets shouldNot be(empty)
  }

  testWithRepo("published DeviceUpdateAssigned message") { implicit ns =>
    val regDev = registerAdminDeviceOk()
    createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

    val msg = msgPub.findReceived[DeviceUpdateEvent] { (msg: DeviceUpdateEvent) =>
      msg.deviceUuid == regDev.deviceId
    }

    msg shouldBe defined
  }

  testWithRepo("Device ignores canceled assignment and sees new assignment created afterwards") {
    implicit ns =>
      val regDev = registerAdminDeviceOk()
      createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

      cancelAssignmentsOk(Seq(regDev.deviceId)) shouldBe Seq(regDev.deviceId)

      val t1 = getTargetsOk(regDev.deviceId)
      t1.signed.targets shouldBe empty

      createDeviceAssignmentOk(regDev.deviceId, regDev.primary.hardwareId)

      val t2 = getTargetsOk(regDev.deviceId)
      // check if a target is addressing our ECU:
      val targetItemCustom = t2.signed.targets.headOption.value._2.customParsed[TargetItemCustom]
      targetItemCustom.get.ecuIdentifiers.keys.head shouldBe regDev.ecus.keys.head
  }

  testWithRepo("can schedule an assignment when using the same ecu serial as another device") {
    implicit ns =>
      val device1 = DeviceId.generate()
      val device2 = DeviceId.generate()
      val (regEcu, _) = GenRegisterEcuKeys.generate
      val regDev1 = RegisterDevice(device1.some, regEcu.ecu_serial, List(regEcu))
      val regDev2 = RegisterDevice(device2.some, regEcu.ecu_serial, List(regEcu))

      Post(apiUri("admin/devices"), regDev1).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }

      Post(apiUri("admin/devices"), regDev2).namespaced ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }

      createDeviceAssignmentOk(device1, regEcu.hardware_identifier)
      createDeviceAssignmentOk(device2, regEcu.hardware_identifier)
  }

  testWithRepo("Retrieving assignments for multiple devices works") { implicit ns =>
    val device1 = DeviceId.generate()
    val device2 = DeviceId.generate()
    val (regEcu, _) = GenRegisterEcuKeys.generate
    val regDev1 = RegisterDevice(device1.some, regEcu.ecu_serial, List(regEcu))
    val regDev2 = RegisterDevice(device2.some, regEcu.ecu_serial, List(regEcu))

    Post(apiUri("admin/devices"), regDev1).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    Post(apiUri("admin/devices"), regDev2).namespaced ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }

    val dev1AssignmentResp = createDeviceAssignmentOk(device1, regEcu.hardware_identifier)
    val dev2AssignmentResp = createDeviceAssignmentOk(device2, regEcu.hardware_identifier)

    getMultipleDeviceAssignments(Set(device1, device2)) {
      status shouldBe StatusCodes.OK
      val res = responseAs[Map[DeviceId, Seq[QueueResponse]]]

      res.get(device1).getOrElse(Seq.empty[QueueResponse]).length shouldBe 1
      res.get(device2).getOrElse(Seq.empty[QueueResponse]).length shouldBe 1

      res
        .get(device1)
        .getOrElse(Seq.empty[QueueResponse])
        .head
        .correlationId shouldBe dev1AssignmentResp.correlationId
      res
        .get(device2)
        .getOrElse(Seq.empty[QueueResponse])
        .head
        .correlationId shouldBe dev2AssignmentResp.correlationId
    }
  }

  testWithRepo("Retrieving assignments for more than 50 devices will limit results to 50") {
    implicit ns =>
      val deviceIds = (1 to 60).toList.map { _ =>
        val deviceId = DeviceId.generate()
        val (regEcu, _) = GenRegisterEcuKeys.generate
        val regDev = RegisterDevice(deviceId.some, regEcu.ecu_serial, List(regEcu))
        Post(apiUri("admin/devices"), regDev).namespaced ~> routes ~> check {
          status shouldBe StatusCodes.Created
        }
        createDeviceAssignmentOk(deviceId, regEcu.hardware_identifier)
        deviceId
      }

      getMultipleDeviceAssignments(deviceIds.toSet) {
        status shouldBe StatusCodes.OK
        val res = responseAs[Map[DeviceId, Seq[QueueResponse]]]
        res.size shouldBe 50
      }
  }

}
