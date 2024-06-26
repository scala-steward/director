package com.advancedtelematic.director.daemon

import java.net.URI
import com.advancedtelematic.director.data.GeneratorOps.*
import com.advancedtelematic.director.data.Generators.*
import com.advancedtelematic.director.db.*
import com.advancedtelematic.director.http.{AdminResources, AssignmentResources}
import com.advancedtelematic.director.util.{DirectorSpec, RepositorySpec, ResourceSpec}
import com.advancedtelematic.libats.data.DataType.AutoUpdateId
import com.advancedtelematic.libtuf.data.ClientDataType.TargetCustom
import com.advancedtelematic.libtuf.data.TufDataType.{TargetName, TargetVersion}
import com.advancedtelematic.libtuf_server.data.Messages.TufTargetAdded
import org.scalatest.LoneElement.*

class TufTargetListenerSpec
    extends DirectorSpec
    with ResourceSpec
    with AssignmentResources
    with AdminResources
    with RepositorySpec
    with EcuTargetsRepositorySupport
    with AutoUpdateDefinitionRepositorySupport
    with AssignmentsRepositorySupport {

  val listener = new TufTargetAddedListener()

  testWithRepo("creates an assignment for ecus that have auto update definition ") { implicit ns =>
    val dev = registerAdminDeviceOk()

    val filename = GenImage.map(_.filepath).generate
    val checksum = GenChecksum.generate
    val uri = new URI("https://here.com")
    val custom =
      TargetCustom(TargetName("mytarget"), TargetVersion("0.0.1"), Seq.empty, None, Some(uri))
    val msg = TufTargetAdded(ns, filename, checksum, 1L, Some(custom))

    val autoUpdateId = autoUpdateDefinitionRepository
      .persist(ns, dev.deviceId, dev.primary.ecuSerial, TargetName("mytarget"))
      .futureValue

    listener.apply(msg).futureValue

    val queue = getDeviceAssignmentOk(dev.deviceId).loneElement

    queue.correlationId shouldBe AutoUpdateId(autoUpdateId.uuid)
    queue.targets(dev.primary.ecuSerial).image.filepath shouldBe filename
    queue.targets(dev.primary.ecuSerial).uri.map(_.toString) should contain(uri.toString)

    val assignment = assignmentsRepository.findBy(dev.deviceId).futureValue.head
    val ecuTarget = ecuTargetsRepository.find(ns, assignment.ecuTargetId).futureValue

    ecuTarget.filename shouldBe filename
    ecuTarget.checksum shouldBe checksum
    ecuTarget.length shouldBe 1L

    val targets = getTargetsOk(dev.deviceId)
    targets.signed.targets.keys.headOption should contain(filename)
  }

  testWithRepo("does not create an assignment when device already has an assignment") {
    implicit ns =>
      val dev = registerAdminDeviceOk()
      createDeviceAssignmentOk(dev.deviceId, dev.primary.hardwareId)

      val filename = GenImage.map(_.filepath).generate
      val checksum = GenChecksum.generate
      val custom =
        TargetCustom(TargetName("mytarget"), TargetVersion("0.0.1"), Seq.empty, None, None)
      val msg = TufTargetAdded(ns, filename, checksum, 1L, Some(custom))

      autoUpdateDefinitionRepository
        .persist(ns, dev.deviceId, dev.primary.ecuSerial, TargetName("mytarget"))
        .futureValue

      listener.apply(msg).futureValue

      val assignments = assignmentsRepository.findBy(dev.deviceId).futureValue.head
      val ecuTarget = ecuTargetsRepository.find(ns, assignments.ecuTargetId).futureValue

      ecuTarget.filename shouldNot be(filename)
  }

}
