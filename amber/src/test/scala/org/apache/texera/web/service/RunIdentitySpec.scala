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

package org.apache.texera.web.service

import org.apache.texera.amber.core.storage.UserTokenProvider
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// RunIdentity together with the provider it registers into, as a computing unit wires them at
// start-up. Only the run queue of a public unit ever sets a current user, so on a private unit
// RunIdentity stays empty for every run.
class RunIdentitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = {
    RunIdentity.clear()
    UserTokenProvider.reset()
  }

  "A private computing unit" should "authenticate as its owner once RunIdentity is installed" in {
    UserTokenProvider.environment = () => Some("owner-jwt")
    RunIdentity.install()
    UserTokenProvider.token shouldBe "owner-jwt"
  }

  "A public computing unit" should "have no token between runs" in {
    UserTokenProvider.environment = () => None
    RunIdentity.install()
    UserTokenProvider.token shouldBe ""
  }
}
