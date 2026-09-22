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

  // No run under way on a public unit: the caller sees an empty token and degrades, rather than
  // reading as whoever happens to be in the pod environment.
  it should "be empty when the supplier has no user" in {
    UserTokenProvider.setSupplier(() => None)
    UserTokenProvider.token shouldBe ""
  }

  // Resolving an identity must never be what breaks an otherwise working read.
  it should "be empty when the supplier throws" in {
    UserTokenProvider.setSupplier(() => throw new RuntimeException("no session"))
    UserTokenProvider.token shouldBe ""
  }

  it should "fall back to the pod environment before a supplier is registered" in {
    // The default supplier reads USER_JWT_TOKEN, which this test JVM does not set; the point is
    // that it resolves to the absent-token state rather than failing.
    UserTokenProvider.reset()
    UserTokenProvider.token shouldBe sys.env.getOrElse("USER_JWT_TOKEN", "").trim
  }
}
