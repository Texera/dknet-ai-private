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

import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.common.config.EnvironmentalVariable

import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.io.Source
import scala.util.Using

/**
  * Makes a versioned LakeFS repository readable inside this computing unit's pod.
  *
  * The mount is not performed here — this pod runs user code and is unprivileged. It asks the
  * mount authority, presenting the pod's own user JWT, and the mount reaches this pod through
  * Kubernetes mount propagation.
  *
  * A repository is addressed by a locator "<repositoryName>:<commitHash>". A commit is
  * immutable, so one mount serves every worker and execution for the life of the pod.
  */
class RepositoryMountManager(
    env: String => Option[String],
    post: (String, String, String) => Unit,
    isMounted: Path => Boolean,
    mountTimeoutMs: Long,
    // The identity to authorize a mount as. Separate from `env` because it is no longer a
    // property of the pod: a public computing unit has no user of its own, and each run must be
    // authorized as whoever started it.
    userToken: () => String
) extends LazyLogging {

  private val mapper = new ObjectMapper()

  private def inPodMountRoot: Path =
    Paths.get(
      env(EnvironmentalVariable.ENV_MOUNT_IN_POD_ROOT)
        .map(_.trim)
        .filter(_.nonEmpty)
        .getOrElse(
          throw new IllegalStateException(
            s"${EnvironmentalVariable.ENV_MOUNT_IN_POD_ROOT} is not set in this computing unit."
          )
        )
    )

  private def parseLocator(locator: String): (String, String) =
    Option(locator).getOrElse("").split(":", 2) match {
      case Array(repository, commit) if repository.nonEmpty && commit.nonEmpty =>
        (repository, commit)
      case _ =>
        throw new IllegalArgumentException(
          s"Invalid mount locator '$locator'; expected <repositoryName>:<commitHash>."
        )
    }

  /**
    * Where `locator` is, or will be, readable in this pod. Pure — it neither mounts nor checks
    * anything, because an operator is given this path while its execution is being set up,
    * before the region is launched and the mount actually happens.
    */
  def mountPointOf(locator: String): Path = {
    val (repository, commit) = parseLocator(locator)
    inPodMountRoot.resolve(repository).resolve(commit)
  }

  /**
    * Mount every locator, deduplicated, in one place rather than once per worker.
    *
    * Warning: a FUSE mount is visible only in the mount namespace of the process that receives
    * it, which is fine while a region's controller and workers share one pod. Were workers ever
    * to become separate pods, this step — and only this step — would have to be dispatched to
    * each of them.
    */
  def ensureAllMounted(locators: Set[String]): Unit = locators.foreach(ensureMounted)

  /** Mount `locator` if it is not already mounted, and return its in-pod path. */
  def ensureMounted(locator: String): Path =
    synchronized {
      val (repository, commit) = parseLocator(locator)
      val mountPoint = mountPointOf(locator)

      if (isMounted(mountPoint)) {
        logger.info(s"$locator is already mounted at $mountPoint")
        return mountPoint
      }

      val Seq(accessControlService, cuid) = Seq(
        EnvironmentalVariable.ENV_ACCESS_CONTROL_SERVICE_URL,
        EnvironmentalVariable.ENV_CU_ID
      ).map(name =>
        env(name).map(_.trim).filter(_.nonEmpty).getOrElse {
          throw new IllegalStateException(s"$name is not set in this computing unit.")
        }
      )

      // The run's own identity, not the pod's. UserTokenProvider falls back to the pod variable
      // for a private unit, so this is the same token as before there.
      val jwt = Option(userToken()).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw new IllegalStateException(
          s"${EnvironmentalVariable.ENV_USER_JWT_TOKEN} is not set in this computing unit, and " +
            "no user is registered for the current run."
        )
      }

      val body = mapper.createObjectNode()
      body.put("repositoryName", repository)
      body.put("commitHash", commit)
      logger.info(s"requesting a mount of $locator for computing unit $cuid")
      post(s"$accessControlService/api/mounts/$cuid", body.toString, jwt)

      // A successful response is not yet a readable directory: GeeseFS is daemonized, and the
      // mount still has to propagate in.
      val deadline = System.currentTimeMillis() + mountTimeoutMs
      while (!isMounted(mountPoint)) {
        if (System.currentTimeMillis() > deadline) {
          // The mounter reported success, so "the mount failed" is usually the wrong reading.
          // The common cause is that this pod's bind of the mount root is on a directory the
          // host deleted underneath it: mounts then keep succeeding on the host and stay
          // invisible in here, forever. The kernel records that as a "//deleted" root, so say
          // so rather than sending the reader to debug the mounter or LakeFS.
          throw new RuntimeException(
            s"$locator did not appear as a mount at $mountPoint within ${mountTimeoutMs}ms." +
              InPodMount
                .deletedMountRootOf(inPodMountRoot)
                .map(root =>
                  s" This computing unit's mount root ($root) has been deleted on the host, so" +
                    " no mount can ever become visible in this pod -- every later mount will" +
                    " succeed on the host and be invisible here. The unit has to be recreated."
                )
                .getOrElse(
                  " The mounter reported success, so check mount propagation into this pod" +
                    " rather than the mounter or the object store."
                )
          )
        }
        Thread.sleep(200)
      }
      logger.info(s"$locator mounted at $mountPoint")
      mountPoint
    }
}

object RepositoryMountManager
    extends RepositoryMountManager(
      name => sys.env.get(name),
      InPodMount.postJson,
      InPodMount.isFuseMounted,
      35000,
      () => UserTokenProvider.token
    )

private object InPodMount {

  def postJson(url: String, body: String, jwt: String): Unit = {
    val connection = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Authorization", s"Bearer $jwt")
    connection.setDoOutput(true)
    connection.setConnectTimeout(10000)
    connection.setReadTimeout(40000)
    try {
      Using(connection.getOutputStream)(_.write(body.getBytes(StandardCharsets.UTF_8)))
      val code = connection.getResponseCode
      if (code < 200 || code >= 300) {
        val error = Option(connection.getErrorStream)
          .map(stream => new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .getOrElse("")
        throw new RuntimeException(s"the mount request was refused: HTTP $code $error")
      }
    } finally {
      connection.disconnect()
    }
  }

  /** The mountinfo root of `mountPoint`'s own mount, if the host has deleted it.
    *
    * /proc/self/mountinfo field 4 is the directory within the source filesystem that is
    * mounted here, and the kernel appends "//deleted" to it once that directory is removed.
    * The mount then still works, but it refers to an orphaned inode, so nothing the host
    * later creates at that path is visible through it. Returns the recorded root, so the
    * caller can quote it.
    */
  def deletedMountRootOf(mountPoint: Path): Option[String] = {
    val target = mountPoint.toAbsolutePath.toString
    Using(Source.fromFile("/proc/self/mountinfo")) { source =>
      source
        .getLines()
        .flatMap { line =>
          val fields = line.split(" ")
          // 0 id, 1 parent, 2 major:minor, 3 root, 4 mount point
          if (fields.length > 4 && fields(4) == target && fields(3).endsWith("/deleted")) {
            Some(fields(3))
          } else None
        }
        .nextOption()
    }.getOrElse(None)
  }

  // /proc/mounts is the kernel's read-only view of this pod's mount table: the kernel adds the
  // entry when a propagated GeeseFS mount arrives and removes it on unmount, so nothing writes it.
  def isFuseMounted(mountPoint: Path): Boolean = {
    if (!Files.exists(mountPoint)) {
      return false
    }
    val target = mountPoint.toAbsolutePath.toString
    Using(Source.fromFile("/proc/mounts")) { source =>
      source.getLines().exists { line =>
        val fields = line.split(" ")
        fields.length > 2 && fields(1) == target && fields(2).startsWith("fuse")
      }
    }.getOrElse(false)
  }
}
