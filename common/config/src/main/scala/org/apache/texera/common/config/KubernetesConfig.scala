/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.texera.common.config

import com.typesafe.config.{Config, ConfigFactory}

object KubernetesConfig {

  private val conf: Config = ConfigFactory.parseResources("kubernetes.conf").resolve()

  val kubernetesComputingUnitEnabled: Boolean = conf.getBoolean("kubernetes.enabled")

  // Access the Kubernetes settings with environment variable fallback
  val computeUnitServiceName: String = conf.getString("kubernetes.compute-unit-service-name")
  val computeUnitPoolName: String = conf.getString("kubernetes.compute-unit-pool-name")
  val computeUnitPoolNamespace: String = conf.getString("kubernetes.compute-unit-pool-namespace")
  val computeUnitPodNamePrefix: String = conf.getString("kubernetes.compute-unit-pod-name-prefix")
  val computeUnitImageName: String = conf.getString("kubernetes.image-name")
  val computingUnitImagePullPolicy: String = conf.getString("kubernetes.image-pull-policy")

  val computeUnitPortNumber: Int = conf.getInt("kubernetes.port-num")

  val maxNumOfRunningComputingUnitsPerUser: Int =
    conf.getInt("kubernetes.max-num-of-running-computing-units-per-user")

  val cpuLimitOptions: List[String] =
    conf
      .getString("kubernetes.computing-unit-cpu-limit-options")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  val memoryLimitOptions: List[String] =
    conf
      .getString("kubernetes.computing-unit-memory-limit-options")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  val gpuLimitOptions: List[String] =
    conf
      .getString("kubernetes.computing-unit-gpu-limit-options")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  // GPU resource key used directly in Kubernetes resource specifications
  val gpuResourceKey: String = conf.getString("kubernetes.computing-unit-gpu-resource-key")

  // Node label key used to identify GPU model on each worker node
  val gpuNodeLabelKey: String = conf.getString("kubernetes.computing-unit-gpu-node-label-key")

  /**
    * GPU model -> the Kubernetes resource that backs it, parsed from
    * "H100=nvidia.com/h100,H200=nvidia.com/h200". Empty when the deployment advertises one
    * flat GPU resource, which is the only case a node label can describe correctly.
    * Malformed pairs are dropped rather than failing startup: a typo here must not take the
    * service down, it just leaves that model on the flat resource.
    */
  val gpuModelResourceKeys: Map[String, String] =
    conf
      .getString("kubernetes.computing-unit-gpu-model-resource-keys")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { pair =>
        pair.split("=", 2) match {
          case Array(model, key) if model.trim.nonEmpty && key.trim.nonEmpty =>
            Some(model.trim -> key.trim)
          case _ => None
        }
      }
      .toMap

  /** Every GPU resource this deployment may schedule against, flat key included. */
  val allGpuResourceKeys: Set[String] = gpuModelResourceKeys.values.toSet + gpuResourceKey

  def isGpuResourceKey(key: String): Boolean = allGpuResourceKeys.contains(key)

  /** "Any" means the user opted out of pinning, so it is never a model in its own right. */
  private def pinnedModel(model: Option[String]): Option[String] =
    model.map(_.trim).filter(m => m.nonEmpty && m != "Any")

  /** True when requesting this model's own resource is what selects the card. */
  def gpuModelHasOwnResource(model: Option[String]): Boolean =
    pinnedModel(model).exists(gpuModelResourceKeys.contains)

  /** The resource a GPU model is requested through; the flat key when it has none. */
  def gpuResourceKeyFor(model: Option[String]): String =
    pinnedModel(model).flatMap(gpuModelResourceKeys.get).getOrElse(gpuResourceKey)

  // Per-user persistent storage: each user gets an isolated PVC created dynamically by the manager
  val userStorageEnabled: Boolean = conf.getBoolean("kubernetes.user-storage-enabled")
  val userStorageClass: String = conf.getString("kubernetes.user-storage-class")
  val userStorageSize: String = conf.getString("kubernetes.user-storage-size")
  val userStorageMountPath: String = conf.getString("kubernetes.user-storage-mount-path")

  /** UIDs allowed to have persistent storage. Empty set means all users are allowed. */
  val userStorageAllowedUids: Set[Int] =
    conf
      .getString("kubernetes.user-storage-allowed-uids")
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.toInt)
      .toSet

  def isUserStorageAllowed(uid: Int): Boolean =
    userStorageEnabled && (userStorageAllowedUids.isEmpty || userStorageAllowedUids.contains(uid))
  // Per-user JupyterLab pods, gated independently of computing units.
  val jupyterEnabled: Boolean = conf.getBoolean("kubernetes.jupyter-enabled")
  val jupyterNamespace: String = conf.getString("kubernetes.jupyter-namespace")
  val jupyterServiceName: String = conf.getString("kubernetes.jupyter-service-name")
  val jupyterImageName: String = conf.getString("kubernetes.jupyter-image-name")
  val jupyterPortNumber: Int = conf.getInt("kubernetes.jupyter-port-num")
  val jupyterBaseUrl: String = conf.getString("kubernetes.jupyter-base-url")
  val jupyterTexeraOrigin: String = conf.getString("kubernetes.jupyter-texera-origin")
  val jupyterCpuLimit: String = conf.getString("kubernetes.jupyter-cpu-limit")
  val jupyterMemoryLimit: String = conf.getString("kubernetes.jupyter-memory-limit")

  // Browser-facing address with {uid} substituted; empty means use the in-network one.
  val jupyterPublicUrlTemplate: String =
    conf.getString("kubernetes.jupyter-public-url-template")

  // Whether the deployment opted into out-of-pod dataset mounting. When false the CU pod is
  // built exactly as it was before the feature existed -- no hostPath, no mount env -- so a
  // cluster enforcing a Pod Security Standard on the pool namespace is unaffected.
  val mounterEnabled: Boolean = conf.getBoolean("kubernetes.mounter-enabled")

  // Root of the per-node mounter's host directory. This service never talks to the mounter
  // -- access-control-service does -- but it builds the CU pod spec, and the pod's hostPath
  // must be the <root>/<cuid> subtree the mounter mounts into.
  val mounterHostRoot: String = conf.getString("kubernetes.mounter-host-root")

  // See kubernetes.conf on why the uid has to be given alongside runAsNonRoot.
  val computingUnitRunAsNonRoot: Boolean =
    conf.getBoolean("kubernetes.computing-unit-run-as-non-root")
  val computingUnitRunAsUser: Long = conf.getLong("kubernetes.computing-unit-run-as-user")

}
