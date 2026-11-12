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

import org.apache.texera.common.config.EnvironmentalVariable

/**
  * The JWT that file reads authenticate with.
  *
  * A computing unit created by one user carries that user's token in its pod environment, which
  * is right for a private unit and wrong for a public one: every user's run would read datasets
  * as the administrator who created it. So the token is resolved per call instead, from whatever
  * the process registers as the current run's identity, falling back to the pod's own variable
  * for units that still have one.
  *
  * The supplier is registered by the web layer (which knows who is running) rather than passed
  * in, because the readers are reached through `DocumentFactory`, a static factory with no
  * caller context; threading an identity through every operator instead would be a far larger
  * change for the same result. It is well defined because a public unit runs one workflow at a
  * time — that is what its queue is for.
  */
object UserTokenProvider {

  private val podEnvironment: () => Option[String] =
    () => sys.env.get(EnvironmentalVariable.ENV_USER_JWT_TOKEN)

  private val noRun: () => Option[String] = () => None

  /** Where the pod's own token is read from. Replaceable so tests need not set the real variable. */
  @volatile private[texera] var environment: () => Option[String] = podEnvironment

  @volatile private var supplier: () => Option[String] = noRun

  /**
    * Register where the token comes from. Called once at start-up by the process that knows who
    * is running; the supplier is consulted on every read, so it can return a different token as
    * the current run changes.
    */
  def setSupplier(newSupplier: () => Option[String]): Unit = supplier = newSupplier

  /** Restores the defaults: no registered run, and the real pod environment. For tests. */
  private[texera] def reset(): Unit = {
    supplier = noRun
    environment = podEnvironment
  }

  /**
    * The token to authenticate the current read with, trimmed; empty when there is none, which
    * readers take as "no user to act for" and fall back to direct storage access.
    *
    * The current run's identity wins; without one, the pod's own variable is used. The fallback
    * lives here and not in a supplier because registering a supplier replaces the previous one:
    * a supplier that only knows about queued runs would otherwise leave a private unit, whose
    * runs are never queued, with no token at all. A public unit has no pod variable, so the
    * fallback cannot make it read as whoever created it.
    */
  def token: String =
    nonBlank(supplier).orElse(nonBlank(environment)).getOrElse("")

  // Never let resolving an identity break a read that would otherwise work: a source that throws
  // counts as having no token, and the caller degrades accordingly.
  private def nonBlank(source: () => Option[String]): Option[String] =
    try source().map(_.trim).filter(_.nonEmpty)
    catch { case _: Throwable => None }
}
