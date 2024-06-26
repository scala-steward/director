package com.advancedtelematic.director.repo

import com.advancedtelematic.director.data.DbDataType.Assignment
import com.advancedtelematic.director.db.{
  AssignmentsRepositorySupport,
  DbDeviceRoleRepositorySupport,
  EcuTargetsRepositorySupport,
  ProvisionedDeviceRepositorySupport
}
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libtuf.data.ClientDataType.{TargetsRole, TufRole}
import com.advancedtelematic.libtuf.data.TufDataType.{JsonSignedPayload, RepoId}
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import com.advancedtelematic.libtuf_server.repo.server._
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContext, Future}

class DeviceRoleGeneration(keyserverClient: KeyserverClient)(
  implicit val db: Database,
  val ec: ExecutionContext)
    extends AssignmentsRepositorySupport
    with DbDeviceRoleRepositorySupport
    with EcuTargetsRepositorySupport
    with ProvisionedDeviceRepositorySupport {

  import scala.async.Async._

  private val _log = LoggerFactory.getLogger(this.getClass)

  private val roleGeneration = (ns: Namespace, device: DeviceId) => {
    val itemsProvider = new DeviceTargetProvider(ns, device)
    val signedRoleProvider = new DeviceSignedRoleProvider(device)
    new SignedRoleGeneration(keyserverClient, itemsProvider, signedRoleProvider)
  }

  private val roleRefresher = (ns: Namespace, device: DeviceId) => {
    val itemsProvider = new DeviceTargetProvider(ns, device)
    val signedRoleProvider = new DeviceSignedRoleProvider(device)
    new RepoRoleRefresh(keyserverClient, signedRoleProvider, itemsProvider)
  }

  def findFreshTargets(ns: Namespace,
                       repoId: RepoId,
                       deviceId: DeviceId): Future[(JsonSignedPayload, Seq[Assignment])] = async {
    val isOutdated = await(provisionedDeviceRepository.metadataIsOutdated(ns, deviceId))

    if (isOutdated) {
      _log.info(s"targets for $deviceId is outdated")
      val t = await(roleGeneration(ns, deviceId).regenerateAllSignedRoles(repoId))
      val regenerated =
        await(assignmentsRepository.markRegenerated(provisionedDeviceRepository)(deviceId))
      t -> regenerated
    } else { // return existing/refreshed targets
      implicit val refresher = roleRefresher(ns, deviceId)
      val targets = await(roleGeneration(ns, deviceId).findRole[TargetsRole](repoId))
      targets.content -> Seq.empty
    }
  }

  def forceTargetsRefresh(deviceId: DeviceId): Future[Unit] = {
    _log.info(s"Forcing refresh of metadata for $deviceId")
    provisionedDeviceRepository.setMetadataOutdated(deviceId, outdated = true)
  }

  def findFreshDeviceRole[T: TufRole](ns: Namespace,
                                      repoId: RepoId,
                                      deviceId: DeviceId): Future[JsonSignedPayload] = {
    implicit val refresher = roleRefresher(ns, deviceId)
    roleGeneration(ns, deviceId).findRole[T](repoId).map(_.content)
  }

}
