/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.service.resource

import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.core.Response
import org.apache.texera.auth.SessionUser
import org.apache.texera.common.config.ComputingUnitConfig
import org.apache.texera.dao.MockTexeraDB
import org.apache.texera.dao.jooq.generated.Tables.WORKFLOW_COMPUTING_UNIT
import org.apache.texera.dao.jooq.generated.enums.{
  ComputingUnitAccessScopeEnum,
  UserRoleEnum,
  WorkflowComputingUnitTypeEnum
}
import org.apache.texera.dao.jooq.generated.tables.daos.{UserDao, WorkflowComputingUnitDao}
import org.apache.texera.dao.jooq.generated.tables.pojos.{User, WorkflowComputingUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

/**
  * A public computing unit is visible to, and usable by, everybody, but managed only by
  * administrators. Only local units are created here: a kubernetes one would reach for the real
  * KubernetesClient singleton.
  */
class PublicComputingUnitSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with MockTexeraDB {

  private val adminUid = 1
  private val regularUid = 2

  private lazy val resource = new ComputingUnitManagingResource()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    initializeDBAndReplaceDSLContext()
    val userDao = new UserDao(getDSLContext.configuration())
    userDao.insert(makeUser(adminUid, "admin", UserRoleEnum.ADMIN))
    userDao.insert(makeUser(regularUid, "regular", UserRoleEnum.REGULAR))
  }

  // MockTexeraDB does not truncate between tests and the listing sees every unit, so each case
  // starts from an empty table.
  override protected def beforeEach(): Unit = {
    super.beforeEach()
    getDSLContext.deleteFrom(WORKFLOW_COMPUTING_UNIT).execute()
  }

  override protected def afterAll(): Unit =
    try shutdownDB()
    finally super.afterAll()

  private def makeUser(uid: Int, name: String, role: UserRoleEnum): User = {
    val u = new User()
    u.setUid(uid)
    u.setName(name)
    u.setEmail(s"$name@example.com")
    u.setRole(role)
    u
  }

  private def session(uid: Int, role: UserRoleEnum): SessionUser =
    new SessionUser(makeUser(uid, if (role == UserRoleEnum.ADMIN) "admin" else "regular", role))

  /** Insert a unit directly, bypassing pod creation. */
  private def insertUnit(
      cuid: Int,
      uid: Int,
      name: String,
      scope: ComputingUnitAccessScopeEnum
  ): WorkflowComputingUnit = {
    val unit = new WorkflowComputingUnit()
    unit.setCuid(cuid)
    unit.setUid(uid)
    unit.setName(name)
    unit.setType(WorkflowComputingUnitTypeEnum.local)
    unit.setUri("http://localhost:8085/wsapi")
    unit.setAccessScope(scope)
    new WorkflowComputingUnitDao(getDSLContext.configuration()).insert(unit)
    unit
  }

  // Guards the build.sbt test grouping: without the flag the listing branch below is dead code
  // and every assertion would pass vacuously.
  "the test JVM" should "have public computing units enabled" in {
    ComputingUnitConfig.publicComputingUnitEnabled shouldBe true
  }

  "AdminComputingUnitResource" should "expose public creation only to ADMIN" in {
    val annotation = classOf[AdminComputingUnitResource].getAnnotation(classOf[RolesAllowed])
    annotation should not be null
    annotation.value.toSeq shouldBe Seq("ADMIN")
  }

  it should "persist access_scope = public when created through the admin endpoint" in {
    new AdminComputingUnitResource().createPublicComputingUnit(
      ComputingUnitManagingResource.WorkflowComputingUnitCreationParams(
        name = "shared unit",
        unitType = "local",
        cpuLimit = "NaN",
        memoryLimit = "NaN",
        gpuLimit = "NaN",
        jvmMemorySize = "NaN",
        shmSize = "NaN",
        uri = Some("http://localhost:8085/wsapi")
      ),
      session(adminUid, UserRoleEnum.ADMIN)
    )

    val stored = getDSLContext
      .selectFrom(WORKFLOW_COMPUTING_UNIT)
      .fetchInto(classOf[WorkflowComputingUnit])
    stored should have size 1
    stored.get(0).getAccessScope shouldBe ComputingUnitAccessScopeEnum.PUBLIC
    // The creator is recorded, but the unit is not theirs to keep.
    stored.get(0).getUid shouldBe adminUid
  }

  // The /create endpoint has no access-scope parameter at all, so there is no request body a
  // regular user could send that produces a public unit.
  it should "create a private unit through the ordinary endpoint" in {
    resource.createWorkflowComputingUnit(
      ComputingUnitManagingResource.WorkflowComputingUnitCreationParams(
        name = "my unit",
        unitType = "local",
        cpuLimit = "NaN",
        memoryLimit = "NaN",
        gpuLimit = "NaN",
        jvmMemorySize = "NaN",
        shmSize = "NaN",
        uri = Some("http://localhost:8085/wsapi")
      ),
      session(regularUid, UserRoleEnum.REGULAR)
    )

    val stored = getDSLContext
      .selectFrom(WORKFLOW_COMPUTING_UNIT)
      .fetchInto(classOf[WorkflowComputingUnit])
    stored.get(0).getAccessScope shouldBe ComputingUnitAccessScopeEnum.PRIVATE
  }

  "listComputingUnits" should "show a public unit to a user who does not own it" in {
    insertUnit(100, adminUid, "shared unit", ComputingUnitAccessScopeEnum.PUBLIC)

    val listed = resource.listComputingUnits(session(regularUid, UserRoleEnum.REGULAR))
    listed.map(_.computingUnit.getCuid) shouldBe List(100)
    listed.head.isOwner shouldBe false
    // Everyone may run on it, which is what WRITE means for a computing unit.
    listed.head.accessPrivilege shouldBe
      org.apache.texera.dao.jooq.generated.enums.PrivilegeEnum.WRITE
  }

  it should "still hide another user's private unit" in {
    insertUnit(101, adminUid, "admin's own unit", ComputingUnitAccessScopeEnum.PRIVATE)

    resource.listComputingUnits(session(regularUid, UserRoleEnum.REGULAR)) shouldBe empty
  }

  it should "list an administrator's own public unit exactly once" in {
    insertUnit(102, adminUid, "shared unit", ComputingUnitAccessScopeEnum.PUBLIC)

    val listed = resource.listComputingUnits(session(adminUid, UserRoleEnum.ADMIN))
    listed.map(_.computingUnit.getCuid) shouldBe List(102)
    listed.head.isOwner shouldBe true
  }

  it should "not show a terminated public unit" in {
    val unit = insertUnit(103, adminUid, "old unit", ComputingUnitAccessScopeEnum.PUBLIC)
    unit.setTerminateTime(new java.sql.Timestamp(System.currentTimeMillis()))
    new WorkflowComputingUnitDao(getDSLContext.configuration()).update(unit)

    resource.listComputingUnits(session(regularUid, UserRoleEnum.REGULAR)) shouldBe empty
  }

  "a public unit" should "refuse termination by a regular user" in {
    insertUnit(104, adminUid, "shared unit", ComputingUnitAccessScopeEnum.PUBLIC)

    val response =
      resource.terminateComputingUnit(104, session(regularUid, UserRoleEnum.REGULAR))
    response.getStatus shouldBe Response.Status.BAD_REQUEST.getStatusCode
    // Still there.
    getDSLContext
      .selectFrom(WORKFLOW_COMPUTING_UNIT)
      .fetchInto(classOf[WorkflowComputingUnit])
      .get(0)
      .getTerminateTime shouldBe null
  }

  it should "refuse renaming by a regular user" in {
    insertUnit(105, adminUid, "shared unit", ComputingUnitAccessScopeEnum.PUBLIC)

    val response =
      resource.renameComputingUnit(105, "hijacked", session(regularUid, UserRoleEnum.REGULAR))
    response.getStatus shouldBe Response.Status.FORBIDDEN.getStatusCode
    getDSLContext
      .selectFrom(WORKFLOW_COMPUTING_UNIT)
      .fetchInto(classOf[WorkflowComputingUnit])
      .get(0)
      .getName shouldBe "shared unit"
  }

  it should "allow an administrator to rename it" in {
    insertUnit(106, adminUid, "shared unit", ComputingUnitAccessScopeEnum.PUBLIC)

    val response =
      resource.renameComputingUnit(106, "renamed unit", session(adminUid, UserRoleEnum.ADMIN))
    response.getStatus shouldBe Response.Status.OK.getStatusCode
    getDSLContext
      .selectFrom(WORKFLOW_COMPUTING_UNIT)
      .fetchInto(classOf[WorkflowComputingUnit])
      .get(0)
      .getName shouldBe "renamed unit"
  }
}
