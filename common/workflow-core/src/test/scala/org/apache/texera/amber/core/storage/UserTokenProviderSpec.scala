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

package org.apache.texera.amber.core.storage

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserTokenProviderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // No pod variable unless a test sets one, so results do not depend on the machine running them.
  override def beforeEach(): Unit = UserTokenProvider.environment = () => None

  override def afterEach(): Unit = UserTokenProvider.reset()

  "UserTokenProvider" should "return what the registered supplier gives" in {
    UserTokenProvider.setSupplier(() => Some("run-jwt"))
    UserTokenProvider.token shouldBe "run-jwt"
  }

  // The token is resolved per read rather than memoised, so a unit that runs one user's workflow
  // after another's does not keep reading files as the first.
  it should "follow the supplier as the current run changes" in {
    var current = "first-user"
    UserTokenProvider.setSupplier(() => Some(current))
    UserTokenProvider.token shouldBe "first-user"

    current = "second-user"
    UserTokenProvider.token shouldBe "second-user"
  }

  it should "trim what the supplier returns" in {
    UserTokenProvider.setSupplier(() => Some("  padded-jwt \n"))
    UserTokenProvider.token shouldBe "padded-jwt"
  }

  // No run under way on a public unit, which has no pod variable: the caller sees an empty token
  // and degrades.
  it should "be empty when the supplier has no user" in {
    UserTokenProvider.setSupplier(() => None)
    UserTokenProvider.token shouldBe ""
  }

  // Resolving an identity must never be what breaks an otherwise working read.
  it should "be empty when the supplier throws" in {
    UserTokenProvider.setSupplier(() => throw new RuntimeException("no session"))
    UserTokenProvider.token shouldBe ""
  }

  it should "use the pod's token when no supplier is registered" in {
    UserTokenProvider.environment = () => Some("pod-jwt")
    UserTokenProvider.token shouldBe "pod-jwt"
  }

  // A private unit: the web layer registers a supplier, but it only knows about queued runs, and a
  // private unit's runs are never queued. Its reads must still authenticate as the unit's owner.
  it should "fall back to the pod's token when the supplier has no user" in {
    UserTokenProvider.environment = () => Some("owner-jwt")
    UserTokenProvider.setSupplier(() => None)
    UserTokenProvider.token shouldBe "owner-jwt"
  }

  it should "prefer the current run's token over the pod's" in {
    UserTokenProvider.environment = () => Some("owner-jwt")
    UserTokenProvider.setSupplier(() => Some("run-jwt"))
    UserTokenProvider.token shouldBe "run-jwt"
  }

  it should "fall back to the pod's token when the supplier gives a blank one" in {
    UserTokenProvider.environment = () => Some("owner-jwt")
    UserTokenProvider.setSupplier(() => Some("   "))
    UserTokenProvider.token shouldBe "owner-jwt"
  }

  it should "fall back to the pod's token when the supplier throws" in {
    UserTokenProvider.environment = () => Some("owner-jwt")
    UserTokenProvider.setSupplier(() => throw new RuntimeException("no session"))
    UserTokenProvider.token shouldBe "owner-jwt"
  }

  it should "trim the pod's token" in {
    UserTokenProvider.environment = () => Some(" owner-jwt\n")
    UserTokenProvider.token shouldBe "owner-jwt"
  }

  it should "be empty when the pod's variable cannot be read" in {
    UserTokenProvider.environment = () => throw new SecurityException("no env access")
    UserTokenProvider.token shouldBe ""
  }
}
