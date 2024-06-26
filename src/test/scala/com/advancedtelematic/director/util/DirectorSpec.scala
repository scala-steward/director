package com.advancedtelematic.director.util

import java.security.Security
import akka.http.scaladsl.model.{headers, HttpRequest}
import com.advancedtelematic.director.data.GeneratorOps._
import com.advancedtelematic.director.http.AdminResources
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.test.InstantMatchers
import com.typesafe.config.{Config, ConfigFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.scalacheck.Gen
import org.scalactic.source.Position
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

trait NamespacedTests {

  def withRandomNamespace[T](fn: Namespace => T): T =
    fn(Namespace(Gen.alphaChar.listBetween(100, 150).generate.mkString))

  implicit class Namespaced(value: HttpRequest) {

    def namespaced(implicit namespace: Namespace): HttpRequest =
      value.addHeader(headers.RawHeader("x-ats-namespace", namespace.get))

  }

}

object NamespacedTests extends NamespacedTests

abstract class DirectorSpec
    extends AnyFunSuite
    with Matchers
    with ScalaFutures
    with NamespacedTests
    with InstantMatchers
    with DefaultPatience {

  Security.addProvider(new BouncyCastleProvider())

  val testDbConfig: Config = ConfigFactory.load().getConfig("ats.director-v2.database")

  def testWithNamespace(testName: String, testArgs: Tag*)(testFun: Namespace => Any)(
    implicit pos: Position): Unit =
    test(testName, testArgs*)(withRandomNamespace(testFun))(pos = pos)

}

trait RepositorySpec {
  self: AdminResources & DirectorSpec =>

  def testWithRepo(testName: String, testArgs: Tag*)(testFun: Namespace => Any)(
    implicit pos: Position): Unit = {
    val testFn = (ns: Namespace) => {
      createRepoOk()(ns = ns, pos = pos)
      testFun(ns)
    }

    testWithNamespace(testName, testArgs: _*)(testFn)
  }

}
