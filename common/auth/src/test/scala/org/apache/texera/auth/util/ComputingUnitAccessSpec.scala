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

package org.apache.texera.auth.util

import org.apache.texera.dao.MockTexeraDB
import org.apache.texera.dao.jooq.generated.enums.{ComputingUnitAccessScopeEnum, PrivilegeEnum}
import org.apache.texera.dao.jooq.generated.tables.daos.{
  ComputingUnitUserAccessDao,
  UserDao,
  WorkflowComputingUnitDao
}
import org.apache.texera.dao.jooq.generated.tables.pojos.{
  ComputingUnitUserAccess,
  User,
  WorkflowComputingUnit
}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// Exercises getComputingUnitAccess against an embedded Postgres seeded with a
// computing unit (cuid=100) owned by uid=1, plus a READ grant (uid=2) and a
// WRITE grant (uid=3). Covers every branch of the single-join resolution:
// missing unit, owner, explicit grant, and a user with no grant.
//
// Two more units cover access_scope: a live public unit (cuid=200) and a terminated one
// (cuid=201), both owned by uid=1, neither with any access row.
class ComputingUnitAccessSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with MockTexeraDB {

  private val cuid = 100
  private val publicCuid = 200
  private val terminatedPublicCuid = 201

  private def newUser(uid: Int, name: String): User = {
    val u = new User
    u.setUid(uid)
    u.setName(name)
    u
  }

  override def beforeAll(): Unit = {
    initializeDBAndReplaceDSLContext()
    val config = getDSLContext.configuration()

    val userDao = new UserDao(config)
    Seq(1 -> "owner", 2 -> "reader", 3 -> "writer", 4 -> "stranger").foreach {
      case (uid, name) => userDao.insert(newUser(uid, name))
    }

    val unitDao = new WorkflowComputingUnitDao(config)

    val unit = new WorkflowComputingUnit
    unit.setUid(1)
    unit.setName("cu")
    unit.setCuid(cuid)
    unitDao.insert(unit)

    val publicUnit = new WorkflowComputingUnit
    publicUnit.setUid(1)
    publicUnit.setName("public cu")
    publicUnit.setCuid(publicCuid)
    publicUnit.setAccessScope(ComputingUnitAccessScopeEnum.PUBLIC)
    unitDao.insert(publicUnit)

    val terminatedPublicUnit = new WorkflowComputingUnit
    terminatedPublicUnit.setUid(1)
    terminatedPublicUnit.setName("terminated public cu")
    terminatedPublicUnit.setCuid(terminatedPublicCuid)
    terminatedPublicUnit.setAccessScope(ComputingUnitAccessScopeEnum.PUBLIC)
    terminatedPublicUnit.setTerminateTime(new java.sql.Timestamp(System.currentTimeMillis()))
    unitDao.insert(terminatedPublicUnit)

    val accessDao = new ComputingUnitUserAccessDao(config)
    Seq(2 -> PrivilegeEnum.READ, 3 -> PrivilegeEnum.WRITE).foreach {
      case (uid, privilege) =>
        val access = new ComputingUnitUserAccess
        access.setCuid(cuid)
        access.setUid(uid)
        access.setPrivilege(privilege)
        accessDao.insert(access)
    }
  }

  override def afterAll(): Unit = closeConnectionPool()

  "getComputingUnitAccess" should "return NONE when the computing unit does not exist" in {
    ComputingUnitAccess.getComputingUnitAccess(999, 1) shouldBe PrivilegeEnum.NONE
  }

  it should "return WRITE for the owner regardless of any access row" in {
    ComputingUnitAccess.getComputingUnitAccess(cuid, 1) shouldBe PrivilegeEnum.WRITE
  }

  it should "return the granted READ privilege for a non-owner" in {
    ComputingUnitAccess.getComputingUnitAccess(cuid, 2) shouldBe PrivilegeEnum.READ
  }

  it should "return the granted WRITE privilege for a non-owner" in {
    ComputingUnitAccess.getComputingUnitAccess(cuid, 3) shouldBe PrivilegeEnum.WRITE
  }

  it should "return NONE for a user with no access row on an existing unit" in {
    ComputingUnitAccess.getComputingUnitAccess(cuid, 4) shouldBe PrivilegeEnum.NONE
  }

  it should "return WRITE on a public unit for a stranger with no access row" in {
    ComputingUnitAccess.getComputingUnitAccess(publicCuid, 4) shouldBe PrivilegeEnum.WRITE
  }

  it should "return WRITE on a public unit for the administrator who created it" in {
    ComputingUnitAccess.getComputingUnitAccess(publicCuid, 1) shouldBe PrivilegeEnum.WRITE
  }

  // The row outlives the pod, so the blanket grant has to end with the unit; otherwise every
  // user keeps being routed at an address that no longer serves them.
  it should "return NONE on a terminated public unit for a stranger" in {
    ComputingUnitAccess.getComputingUnitAccess(terminatedPublicCuid, 4) shouldBe PrivilegeEnum.NONE
  }

  // Ownership still resolves on a terminated public unit; only the public grant lapses.
  it should "return WRITE on a terminated public unit for its owner" in {
    ComputingUnitAccess.getComputingUnitAccess(terminatedPublicCuid, 1) shouldBe PrivilegeEnum.WRITE
  }
}
