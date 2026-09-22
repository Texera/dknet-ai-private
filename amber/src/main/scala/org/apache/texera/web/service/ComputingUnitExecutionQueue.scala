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
import org.apache.pekko.actor.Cancellable
import org.apache.texera.amber.core.virtualidentity.WorkflowIdentity
import org.apache.texera.amber.engine.architecture.rpc.controlreturns.WorkflowAggregatedState
import org.apache.texera.amber.engine.architecture.rpc.controlreturns.WorkflowAggregatedState.{
  COMPLETED,
  FAILED,
  KILLED,
  TERMINATED
}
import org.apache.texera.amber.engine.common.AmberRuntime
import org.apache.texera.amber.engine.common.executionruntimestate.ExecutionMetadataStore
import org.apache.texera.common.config.ComputingUnitConfig
import org.apache.texera.dao.jooq.generated.tables.pojos.User
import org.apache.texera.web.model.websocket.request.WorkflowExecuteRequest
import org.apache.texera.web.storage.WorkflowQueueStore

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt

object ComputingUnitExecutionQueue extends LazyLogging {

  /**
    * One queue per computing unit, held in memory in the unit's own process.
    *
    * Every websocket for a given cuid is routed to exactly one pod (the access-control service
    * rewrites Host to workflow_computing_unit.uri), so this process sees every run on the unit
    * and needs no coordination to be authoritative. Keyed by cuid rather than being a singleton
    * because in local development one JVM backs several `local` unit rows.
    *
    * Nothing is persisted: on a restart the in-flight executions are gone anyway, so a stored
    * queue would only be a stale one. If a public unit ever gets more than one replica, this has
    * to move to the database (`FOR UPDATE SKIP LOCKED` over a queue table).
    */
  private val queues = new ConcurrentHashMap[Int, ComputingUnitExecutionQueue]()

  private val publicUnitCache = new ConcurrentHashMap[Int, java.lang.Boolean]()

  def forComputingUnit(cuid: Int): ComputingUnitExecutionQueue =
    queues.computeIfAbsent(cuid, id => new ComputingUnitExecutionQueue(id))

  /**
    * Whether runs on this unit have to queue.
    *
    * Cached: it is read on every Run, and a unit's access scope is fixed at creation — nothing
    * flips an existing unit between private and public.
    */
  def isQueuedComputingUnit(
      cuid: Int,
      lookup: Int => Boolean = ComputingUnitScope.isPublic
  ): Boolean = {
    if (!ComputingUnitConfig.publicComputingUnitEnabled) return false
    publicUnitCache
      .computeIfAbsent(cuid, id => java.lang.Boolean.valueOf(lookup(id)))
      .booleanValue()
  }

  /** Test seam: drops every queue and the scope cache. */
  private[service] def resetForTesting(): Unit = {
    queues.clear()
    publicUnitCache.clear()
  }
}

/**
  * Admission control for one public computing unit: one workflow runs at a time, the rest wait
  * their turn, and users take turns rather than batches (see [[FairShareQueue]]).
  *
  * The unit of queueing is a workflow, not a session: WorkflowService is keyed by workflow id
  * within this process, so a second concurrent run of the same workflow would overwrite the
  * first's state store. A resubmission of something already queued or running is refused.
  */
class ComputingUnitExecutionQueue(cuid: Int) extends LazyLogging {

  private case class PendingRun(
      workflowService: WorkflowService,
      request: WorkflowExecuteRequest,
      userOpt: Option[User],
      sessionUri: URI
  )

  private val queue = new FairShareQueue[WorkflowIdentity, PendingRun]
  private val lock = new Object

  /** Cancels the watchdog for the run currently holding the unit. */
  private var runWatchdog: Cancellable = Cancellable.alreadyCancelled

  /**
    * Queue a run, starting it immediately if the unit is idle.
    *
    * @throws IllegalStateException if this workflow is already queued or running here
    */
  def submit(
      workflowService: WorkflowService,
      request: WorkflowExecuteRequest,
      userOpt: Option[User],
      sessionUri: URI
  ): Unit = {
    val uid = userOpt
      .map(_.getUid.intValue())
      .getOrElse(
        throw new IllegalArgumentException(
          "Cannot queue execution: a user id (uid) is required but none was provided."
        )
      )
    val workflowId = workflowService.workflowId

    val alreadyWaiting = lock.synchronized {
      if (queue.contains(workflowId)) {
        true
      } else if (queue.runningKey.contains(workflowId)) {
        throw new IllegalStateException(
          "This workflow is already running on this computing unit."
        )
      } else {
        queue.enqueue(workflowId, uid, PendingRun(workflowService, request, userOpt, sessionUri))
        logger.info(
          s"[cu=$cuid] queued $workflowId for user $uid; ${queue.size} waiting, " +
            s"running=${queue.runningKey.getOrElse("none")}"
        )
        false
      }
    }

    // A second Run on something already waiting means the button was out of date, not that the
    // user wants two runs. Re-publish rather than throw: an exception here is recorded against
    // the *previous* execution's fatal errors, so the user sees a failure belonging to a run
    // that already finished -- and still no sign of the queue they are actually in.
    if (alreadyWaiting) {
      logger.info(s"[cu=$cuid] $workflowId is already waiting; re-publishing its position")
      publishPositions()
    } else {
      advance()
    }
  }

  /**
    * Give up a waiting slot (the user pressed the run button again to cancel). Returns false if
    * this workflow was not waiting, in which case the caller should treat the request as a kill
    * of a live execution instead.
    */
  def cancel(workflowId: WorkflowIdentity): Boolean = {
    val removed = lock.synchronized(queue.remove(workflowId))
    removed.foreach { entry =>
      logger.info(s"[cu=$cuid] $workflowId left the queue")
      clearQueueState(entry.value.workflowService)
    }
    if (removed.isDefined) {
      advance()
    }
    removed.isDefined
  }

  /**
    * The workflow's state was torn down here (its last viewer left and the clean-up deadline
    * passed). Drop it from the queue, and let the next run in if it was the one holding the unit
    * — otherwise an abandoned run that never reached a terminal state would block everybody.
    */
  def onWorkflowDisposed(workflowId: WorkflowIdentity): Unit = {
    val wasWaiting = lock.synchronized(queue.remove(workflowId)).isDefined
    val wasRunning = releaseToken(workflowId)
    if (wasWaiting || wasRunning) {
      advance()
    }
  }

  /** Free the unit and start whatever is next. */
  private def finish(workflowId: WorkflowIdentity, reason: String): Unit = {
    if (releaseToken(workflowId)) {
      logger.info(s"[cu=$cuid] $workflowId released the unit ($reason)")
      advance()
    }
  }

  /**
    * Start whatever is next, then tell everyone still waiting where they stand.
    *
    * In that order: a run admitted straight onto an idle unit must never be told it is queued
    * first, or the user sees "Queued 1/1" flash before their own run starts.
    */
  private def advance(): Unit = {
    pump()
    publishPositions()
  }

  private def releaseToken(workflowId: WorkflowIdentity): Boolean =
    lock.synchronized {
      val released = queue.release(workflowId)
      if (released) {
        runWatchdog.cancel()
        runWatchdog = Cancellable.alreadyCancelled
        // Stop acting as that user. pump() sets the next one before anything can read a file.
        RunIdentity.clear()
      }
      released
    }

  /** Start the next run if the unit is free. */
  private def pump(): Unit = {
    val admitted = lock.synchronized(queue.admitNext())
    admitted.foreach { entry =>
      val run = entry.value
      clearQueueState(run.workflowService)
      logger.info(s"[cu=$cuid] admitting ${entry.key} (user ${entry.uid}, round ${entry.round})")
      // This unit acts as the user whose run it is for as long as the run holds it, so file
      // reads authenticate as them rather than as whoever created the unit. Safe to keep in one
      // place because only one run holds the unit at a time.
      RunIdentity.setCurrentUser(run.userOpt)
      startWatchdog(entry.key)
      try {
        run.workflowService.initExecutionService(run.request, run.userOpt, run.sessionUri)
        watchForCompletion(entry.key, run.workflowService)
      } catch {
        // initExecutionService reports its own failures through the execution's error handler,
        // so reaching here means it could not even get that far. Do not let the unit stay held.
        case t: Throwable =>
          logger.error(s"[cu=$cuid] ${entry.key} could not be started", t)
          finish(entry.key, "failed to start")
      }
    }
  }

  /**
    * Release the unit once this run reaches a terminal state.
    *
    * Subscribing after initExecutionService is safe despite the run having possibly already
    * failed: the metadata store is a BehaviorSubject, so subscribing replays the current state,
    * and a compile error that set FAILED synchronously is delivered immediately rather than lost.
    */
  private def watchForCompletion(
      workflowId: WorkflowIdentity,
      workflowService: WorkflowService
  ): Unit = {
    Option(workflowService.executionService.getValue) match {
      case Some(execution) =>
        execution.executionStateStore.metadataStore.getStateObservable
          .subscribe { state: ExecutionMetadataStore =>
            if (isTerminal(state.state)) {
              finish(workflowId, s"execution ${state.state}")
            }
          }
      case None =>
        // No execution was published, so nothing will ever report it finished.
        logger.error(s"[cu=$cuid] $workflowId started without an execution service")
        finish(workflowId, "no execution service")
    }
  }

  private def isTerminal(state: WorkflowAggregatedState): Boolean =
    state == COMPLETED || state == FAILED || state == KILLED || state == TERMINATED

  /**
    * Reclaim the unit if one run holds it for too long, so a single wedged workflow cannot block
    * every other user of a shared unit indefinitely. Disabled when max-run-seconds is 0.
    *
    * This only gives the queue back: the run itself keeps going, and its own state events keep
    * flowing to whoever is watching it.
    */
  private def startWatchdog(workflowId: WorkflowIdentity): Unit = {
    val maxRunSeconds = ComputingUnitConfig.publicComputingUnitMaxRunSeconds
    if (maxRunSeconds <= 0) return
    lock.synchronized {
      runWatchdog.cancel()
      runWatchdog = AmberRuntime.scheduleCallThroughActorSystem(maxRunSeconds.seconds) {
        logger.warn(
          s"[cu=$cuid] $workflowId held the unit for over ${maxRunSeconds}s; releasing it to the queue"
        )
        finish(workflowId, "exceeded the maximum run time")
      }
    }
  }

  /** Push each waiting workflow its place in line, and clear the admitted one's. */
  private def publishPositions(): Unit = {
    val waiting = lock.synchronized(queue.order)
    val total = waiting.size
    waiting.zipWithIndex.foreach {
      case (entry, index) =>
        entry.value.workflowService.stateStore.queueStore.updateState(_ =>
          WorkflowQueueStore(queued = true, position = index + 1, queueLength = total)
        )
    }
  }

  private def clearQueueState(workflowService: WorkflowService): Unit =
    workflowService.stateStore.queueStore.updateState(_ => WorkflowQueueStore())
}
