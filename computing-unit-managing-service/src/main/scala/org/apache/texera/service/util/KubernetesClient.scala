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

package org.apache.texera.service.util

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.apache.texera.common.config.{EnvironmentalVariable, KubernetesConfig}

import scala.jdk.CollectionConverters._

/**
  * Thin wrapper over the fabric8 Kubernetes client. The production singleton is the companion
  * object below, bound to a real in-cluster client. The fabric8 client is a constructor
  * parameter (not a mutable global) so tests can construct an instance backed by a stubbed
  * client and exercise the passthrough wrappers without a live cluster.
  */
class KubernetesClient(
    client: io.fabric8.kubernetes.client.KubernetesClient,
    // A constructor parameter rather than a direct KubernetesConfig read, so the spec can
    // build a pod both ways: the mount contract below is security- and scheduling-sensitive
    // and needs asserting on, but the default must stay the PodSecurity-safe one.
    mountingEnabled: Boolean = KubernetesConfig.mounterEnabled
) {

  private val namespace: String = KubernetesConfig.computeUnitPoolNamespace
  private val podNamePrefix = KubernetesConfig.computeUnitPodNamePrefix

  def generatePodURI(cuid: Int): String = {
    s"${generatePodName(cuid)}.${KubernetesConfig.computeUnitServiceName}.$namespace.svc.cluster.local:${KubernetesConfig.computeUnitPortNumber}"
  }

  def generatePodName(cuid: Int): String = s"$podNamePrefix-$cuid"

  def podExists(cuid: Int): Boolean = {
    getPodByName(generatePodName(cuid)).isDefined
  }

  def getPodByName(podName: String): Option[Pod] = {
    Option(client.pods().inNamespace(namespace).withName(podName).get())
  }

  /**
    * Phase of every pod in the namespace, keyed by pod name, in one call — so a bulk listing
    * avoids a per-unit lookup. Unfiltered so callers can test a unit's presence by its pod-name
    * key; a pod with no status yet maps to a `null` phase but still appears.
    */
  def getAllPodPhases: Map[String, String] =
    phasesByPodName(client.pods().inNamespace(namespace).list().getItems.asScala)

  /** Pure fabric8 -> map transform: a pod with no status yet maps to a `null` phase. */
  private[util] def phasesByPodName(pods: Iterable[Pod]): Map[String, String] =
    pods
      .map(pod => pod.getMetadata.getName -> Option(pod.getStatus).map(_.getPhase).orNull)
      .toMap

  // Flatten a pod's per-container resource usage into a single metric -> value map.
  private def containerUsage(podMetrics: PodMetrics): Map[String, String] =
    podMetrics.getContainers.asScala.flatMap { container =>
      container.getUsage.asScala.map {
        case (metric, value) => metric -> value.toString
      }
    }.toMap

  /** Pure fabric8 -> map transform over the raw per-pod metrics items. */
  private[util] def metricsByPodName(
      items: Iterable[PodMetrics]
  ): Map[String, Map[String, String]] =
    items.map(podMetrics => podMetrics.getMetadata.getName -> containerUsage(podMetrics)).toMap

  // One namespace-wide metrics call, returning the raw per-pod items.
  private def fetchPodMetricsItems(): Iterable[PodMetrics] =
    client.top().pods().metrics(namespace).getItems.asScala

  /**
    * CPU/memory of every pod in the namespace, keyed by pod name, in one call — the bulk
    * counterpart to the single-unit lookup.
    */
  def getAllPodMetrics: Map[String, Map[String, String]] =
    metricsByPodName(fetchPodMetricsItems())

  def getPodMetrics(cuid: Int): Map[String, String] = {
    val targetPodName = generatePodName(cuid)
    fetchPodMetricsItems()
      .collectFirst {
        case podMetrics if podMetrics.getMetadata.getName == targetPodName =>
          containerUsage(podMetrics)
      }
      .getOrElse(Map.empty[String, String])
  }

  def getPodLimits(cuid: Int): Map[String, String] = {
    getPodByName(generatePodName(cuid))
      .flatMap { pod =>
        pod.getSpec.getContainers.asScala.headOption.map { container =>
          val limitsMap = container.getResources.getLimits.asScala.map {
            case (key, value) => key -> value.toString
          }.toMap

          limitsMap
        }
      }
      .getOrElse(Map.empty[String, String])
  }

  /**
    * Returns GPU model labels for nodes that have enough remaining GPU capacity.
    *
    * For each node carrying the configured GPU node label, the method computes:
    *   remaining = allocatable GPUs − GPUs already requested by running pods
    * and includes the model in the result only when remaining >= requestedCount.
    *
    * "Any" is always prepended so the caller can opt out of model pinning.
    *
    * @param requestedCount number of GPUs the user wants to reserve
    * @return sorted list of available GPU model strings, prefixed with "Any"
    */
  def getAvailableGpuModels(requestedCount: Int): List[String] = {
    val labelKey = KubernetesConfig.gpuNodeLabelKey
    val gpuKey = KubernetesConfig.gpuResourceKey

    val nodes = client
      .nodes()
      .list()
      .getItems
      .asScala
      .filter(n => Option(n.getMetadata.getLabels).exists(_.containsKey(labelKey)))

    val runningPods = client
      .pods()
      .inNamespace(namespace)
      .list()
      .getItems
      .asScala
      .filterNot { p =>
        val phase = Option(p.getStatus.getPhase).getOrElse("")
        phase == "Succeeded" || phase == "Failed"
      }

    val usedPerNode: Map[String, Int] = runningPods
      .groupBy(p => Option(p.getSpec.getNodeName).getOrElse(""))
      .filter(_._1.nonEmpty)
      .map {
        case (nodeName, pods) =>
          val total = pods
            .flatMap(_.getSpec.getContainers.asScala)
            .map { c =>
              Option(c.getResources.getLimits)
                .flatMap(l => Option(l.get(gpuKey)))
                .flatMap(q => Option(q.getAmount).flatMap(_.toIntOption))
                .getOrElse(0)
            }
            .sum
          nodeName -> total
      }
      .toMap

    val available = nodes
      .flatMap { node =>
        val name = node.getMetadata.getName
        val model = node.getMetadata.getLabels.get(labelKey)
        val total = Option(node.getStatus.getAllocatable)
          .flatMap(a => Option(a.get(gpuKey)))
          .flatMap(q => Option(q.getAmount).flatMap(_.toIntOption))
          .getOrElse(0)
        val remaining = total - usedPerNode.getOrElse(name, 0)
        if (remaining >= requestedCount) Some(model) else None
      }
      .distinct
      .sorted
      .toList

    "Any" :: available
  }

  def createPod(
      cuid: Int,
      uid: Int,
      cpuLimit: String,
      memoryLimit: String,
      gpuLimit: String,
      envVars: Map[String, Any],
      shmSize: Option[String] = None,
      gpuModel: Option[String] = None,
      /** A curated image to run instead of the deployment's own. */
      curatedImage: Option[String] = None
  ): Pod = {
    val podName = generatePodName(cuid)
    if (getPodByName(podName).isDefined) {
      throw new Exception(s"Pod with cuid $cuid already exists")
    }

    val baseEnv = envVars.map {
      case (key, value) =>
        new EnvVarBuilder().withName(key).withValue(value.toString).build()
    }.toList

    // Which CU this is, and where its propagated mounts show up. The pod is deliberately
    // not given the mounter's address: only an authenticated platform caller may request a
    // mount, so the address would be of no use to code running here except to probe the
    // node's privileged mounter.
    val inPodMountRoot = "/mnt/texera-mounts"
    val mounterEnv =
      if (!mountingEnabled) Nil
      else
        List(
          new EnvVarBuilder()
            .withName(EnvironmentalVariable.ENV_CU_ID)
            .withValue(cuid.toString)
            .build(),
          new EnvVarBuilder()
            .withName(EnvironmentalVariable.ENV_MOUNT_IN_POD_ROOT)
            .withValue(inPodMountRoot)
            .build()
        )

    val envList = (baseEnv ++ mounterEnv).asJava

    // Setup the resource requirements
    val resourceBuilder = new ResourceRequirementsBuilder()
      .addToLimits("cpu", new Quantity(cpuLimit))
      .addToLimits("memory", new Quantity(memoryLimit))

    // Only add GPU resources if the requested amount is greater than 0
    if (gpuLimit != "0") {
      // Use the configured GPU resource key directly
      resourceBuilder.addToLimits(KubernetesConfig.gpuResourceKey, new Quantity(gpuLimit))
    }

    // Build the pod with metadata
    val podBuilder = new PodBuilder()
      .withNewMetadata()
      .withName(podName)
      .withNamespace(namespace)
      .addToLabels("type", "computing-unit")
      .addToLabels("cuid", cuid.toString)
      .addToLabels("name", podName)

    // Start building the pod spec
    val specBuilder = podBuilder
      .endMetadata()
      .withNewSpec()

    // Only add runtimeClassName when using NVIDIA GPU
    if (gpuLimit != "0" && KubernetesConfig.gpuResourceKey.contains("nvidia")) {
      specBuilder.withRuntimeClassName("nvidia")
    }

    // Pin the pod to a specific GPU model node when the user requested one
    if (gpuLimit != "0") {
      gpuModel.filter(m => m.nonEmpty && m != "Any").foreach { model =>
        specBuilder.withNodeSelector(
          Map(KubernetesConfig.gpuNodeLabelKey -> model).asJava
        )
      }
    }

    // Add main computing unit container
    val containerBuilder = specBuilder
      .addNewContainer()
      .withName("computing-unit-master")
      .withImage(curatedImage.getOrElse(KubernetesConfig.computeUnitImageName))
      .withImagePullPolicy(KubernetesConfig.computingUnitImagePullPolicy)
      .addNewPort()
      .withContainerPort(KubernetesConfig.computeUnitPortNumber)
      .withName("http")
      .endPort()
      .withEnv(envList)
      .withResources(resourceBuilder.build())

    // A curated image was supplied by an administrator and reviewed by nobody, so a unit
    // started from one is pinned to a non-root user with no way to regain privilege.
    //
    // Curated images only. The deployment's own image is its operator's choice, and one
    // that has replaced it with an image needing root would break on upgrade.
    if (curatedImage.isDefined && KubernetesConfig.computingUnitRunAsNonRoot) {
      containerBuilder
        .withNewSecurityContext()
        .withRunAsNonRoot(true)
        .withRunAsUser(KubernetesConfig.computingUnitRunAsUser)
        .withAllowPrivilegeEscalation(false)
        .withNewCapabilities()
        .withDrop("ALL")
        .endCapabilities()
        .endSecurityContext()
    }

    // The FUSE mount is performed by the per-node texera-mounter (privileged), not here,
    // so this pod stays UNPRIVILEGED. It only *receives* the mount via HostToContainer
    // propagation from a host directory scoped to this CU id (see the hostPath volume below).
    if (mountingEnabled) {
      containerBuilder
        .addNewVolumeMount()
        .withName("texera-mounts")
        .withMountPath(inPodMountRoot)
        .withMountPropagation("HostToContainer")
        .endVolumeMount()
    }

    // If shmSize requested, mount /dev/shm
    shmSize.foreach { _ =>
      containerBuilder
        .addNewVolumeMount()
        .withName("dshm")
        .withMountPath("/dev/shm")
        .endVolumeMount()
    }

    // If per-user persistent storage is enabled for this user, mount the user's PVC
    if (KubernetesConfig.isUserStorageAllowed(uid)) {
      containerBuilder
        .addNewVolumeMount()
        .withName("user-data")
        .withMountPath(KubernetesConfig.userStorageMountPath)
        .endVolumeMount()
    }

    containerBuilder.endContainer()

    // Add tmpfs volume if needed
    shmSize.foreach { size =>
      specBuilder
        .addNewVolume()
        .withName("dshm")
        .withEmptyDir(
          new EmptyDirVolumeSourceBuilder()
            .withMedium("Memory")
            .withSizeLimit(new Quantity(size))
            .build()
        )
        .endVolume()
    }

    // Add per-user persistent storage volume if enabled for this user
    if (KubernetesConfig.isUserStorageAllowed(uid)) {
      createUserStoragePvcIfAbsent(uid)
      specBuilder
        .addNewVolume()
        .withName("user-data")
        .withNewPersistentVolumeClaim()
        .withClaimName(generateUserPvcName(uid))
        .endPersistentVolumeClaim()
        .endVolume()
    }

    // Per-CU host directory the mounter mounts into (DirectoryOrCreate so it exists
    // before the mounter mounts). Scoped by cuid so a CU can only ever see its own mounts.
    // Guarded because `baseline` and `restricted` forbid hostPath: on a cluster enforcing
    // either on the pool namespace, an unconditional one makes every CU pod unschedulable.
    if (mountingEnabled) {
      specBuilder
        .addNewVolume()
        .withName("texera-mounts")
        .withNewHostPath()
        .withPath(s"${KubernetesConfig.mounterHostRoot}/$cuid")
        .withType("DirectoryOrCreate")
        .endHostPath()
        .endVolume()
    }

    val pod = specBuilder
      .withHostname(podName)
      .withSubdomain(KubernetesConfig.computeUnitServiceName)
      .endSpec()
      .build()

    client.resource(pod).inNamespace(namespace).create()
  }

  def deletePod(cuid: Int): Unit = {
    client.pods().inNamespace(namespace).withName(generatePodName(cuid)).delete()
  }

  def generateUserPvcName(uid: Int): String = s"user-storage-$uid"

  /** Creates the per-user PVC if it does not yet exist. The PVC persists after pod deletion. */
  def createUserStoragePvcIfAbsent(uid: Int): Unit = {
    val pvcName = generateUserPvcName(uid)
    val existing = client.persistentVolumeClaims().inNamespace(namespace).withName(pvcName).get()
    if (existing == null) {
      val pvc = new PersistentVolumeClaimBuilder()
        .withNewMetadata()
        .withName(pvcName)
        .withNamespace(namespace)
        .endMetadata()
        .withNewSpec()
        .withAccessModes("ReadWriteMany")
        .withStorageClassName(KubernetesConfig.userStorageClass)
        .withNewResources()
        .addToRequests("storage", new Quantity(KubernetesConfig.userStorageSize))
        .endResources()
        .endSpec()
        .build()
      client.resource(pvc).inNamespace(namespace).create()
    }
  }
}

/** Production singleton bound to a real in-cluster fabric8 client. */
object KubernetesClient
    extends KubernetesClient(
      new KubernetesClientBuilder().build(),
      // Passed explicitly: a companion object extending its companion class may not rely on
      // the class's default constructor arguments.
      KubernetesConfig.mounterEnabled
    )
