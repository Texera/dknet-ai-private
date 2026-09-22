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

import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.amber.core.storage.UserTokenProvider
import org.apache.texera.auth.JwtAuth
import org.apache.texera.auth.JwtAuth.jwtClaims
import org.apache.texera.dao.jooq.generated.tables.pojos.User

/**
  * Who the computing unit is currently acting as, so that file reads authenticate as the user
  * whose run is in progress rather than as whoever the unit was created by.
  *
  * A token is minted here from the session's user rather than forwarded from the browser — the
  * unit already holds the signing secret and does exactly this in ResultExportService — so no
  * client credential is stored or relayed.
  *
  * Single-valued because a public computing unit runs one workflow at a time; that is what its
  * queue exists to guarantee. On a private unit nothing sets it at all, so the pod-environment
  * fallback in [[UserTokenProvider]] keeps behaving exactly as before.
  *
  * The "one at a time" guarantee is per computing unit, and in a Kubernetes deployment a unit is
  * a pod, so one process serves one unit and this is exact. It is not exact in local development,
  * where one JVM can back several `local` unit rows: two public local units running at once would
  * share this value and the last admitted run would win. Making this per-unit means threading an
  * identity through DocumentFactory's static readers, which is the refactor this deliberately
  * avoids; until then, do not run more than one public local unit in a single process.
  */
object RunIdentity extends LazyLogging {

  @volatile private var currentUser: Option[User] = None

  /** Register the supplier once, at start-up. */
  def install(): Unit = UserTokenProvider.setSupplier(() => currentToken)

  /** The run that is now under way belongs to this user. */
  def setCurrentUser(user: Option[User]): Unit = {
    currentUser = user
    user.foreach(u => logger.info(s"computing unit is now acting as user ${u.getUid}"))
  }

  /** The run finished; stop acting as anyone. */
  def clear(): Unit = currentUser = None

  /**
    * A freshly minted token for the current user, or None when no run is under way — in which
    * case [[UserTokenProvider]] falls back to the pod's own variable.
    */
  private def currentToken: Option[String] =
    currentUser.map(user => JwtAuth.jwtToken(jwtClaims(user)))
}
