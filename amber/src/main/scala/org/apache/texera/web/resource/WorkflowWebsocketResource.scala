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

package org.apache.texera.web.resource

import com.google.protobuf.timestamp.Timestamp
import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.amber.clustering.ClusterListener
import org.apache.texera.amber.core.virtualidentity.WorkflowIdentity
import org.apache.texera.amber.core.workflowruntimestate.FatalErrorType.COMPILATION_ERROR
import org.apache.texera.amber.core.workflowruntimestate.WorkflowFatalError
import org.apache.texera.amber.error.ErrorUtils.getStackTraceWithAllCauses
import org.apache.texera.amber.util.JSONUtils.objectMapper
import org.apache.texera.auth.util.HeaderField
import org.apache.texera.dao.jooq.generated.enums.PrivilegeEnum
import org.apache.texera.dao.jooq.generated.tables.pojos.User
import org.apache.texera.web.model.websocket.event.{
  WorkflowErrorEvent,
  WorkflowQueueStatusEvent,
  WorkflowStateEvent
}
import org.apache.texera.web.model.websocket.request._
import org.apache.texera.web.model.websocket.response._
import org.apache.texera.web.service.{ComputingUnitExecutionQueue, WorkflowService}
import org.apache.texera.web.{ServletAwareConfigurator, SessionState}

import java.time.Instant
import javax.websocket._
import javax.websocket.server.ServerEndpoint
import scala.jdk.CollectionConverters.MapHasAsScala

@ServerEndpoint(
  value = "/wsapi/workflow-websocket",
  configurator = classOf[ServletAwareConfigurator]
)
class WorkflowWebsocketResource extends LazyLogging {

  @OnOpen
  def myOnOpen(session: Session, config: EndpointConfig): Unit = {
    val sessionState = new SessionState(session)
    SessionState.setState(session.getId, sessionState)
    val wid = session.getRequestParameterMap.get("wid").get(0).toLong
    val cuid = session.getRequestParameterMap.get("cuid").get(0).toInt
    val cuAccessEnum: PrivilegeEnum = PrivilegeEnum.valueOf(
      session.getUserProperties
        .get(HeaderField.UserComputingUnitAccess)
        .asInstanceOf[String]
    )

    sessionState.setUserComputingUnitAccess(cuAccessEnum)
    logger.info(
      s"Websocket connection opened for workflow $wid with computing unit $cuid and access $cuAccessEnum"
    )
    // hack to refresh frontend run button state
    sessionState.send(WorkflowStateEvent("Uninitialized"))
    val workflowState =
      WorkflowService.getOrCreate(WorkflowIdentity(wid), cuid)
    sessionState.subscribe(workflowState)

    // Re-send the queue position, after subscribing, when this workflow is waiting for a public
    // computing unit. subscribe() replays two things in order: the cross-execution stores (which
    // hold the queue position), then the last execution's own state. A workflow that finished a
    // run and was then queued again therefore receives its position first and the previous run's
    // COMPLETED/FAILED state second, and the stale one wins -- the run button falls back to "Run"
    // as though nothing had been submitted. Sending it last makes the queue the final word.
    val queueState = workflowState.stateStore.queueStore.getState
    if (queueState.queued) {
      sessionState.send(
        WorkflowQueueStatusEvent(queueState.queued, queueState.position, queueState.queueLength)
      )
    }

    sessionState.send(ClusterStatusUpdateEvent(ClusterListener.numWorkerNodesInCluster))
  }

  @OnClose
  def myOnClose(session: Session, cr: CloseReason): Unit = {
    SessionState.removeState(session.getId)
  }

  @OnMessage
  def myOnMsg(session: Session, message: String): Unit = {
    val request = objectMapper.readValue(message, classOf[TexeraWebSocketRequest])
    val userOpt = session.getUserProperties.asScala
      .get(classOf[User].getName)
      .map(_.asInstanceOf[User])
    val uidOpt = userOpt.map(_.getUid)

    val sessionState = SessionState.getState(session.getId)
    val workflowStateOpt = sessionState.getCurrentWorkflowState
    val executionStateOpt = workflowStateOpt.flatMap(x => Option(x.executionService.getValue))
    try {
      request match {
        case heartbeat: HeartBeatRequest =>
          sessionState.send(HeartBeatResponse())
        case paginationRequest: ResultPaginationRequest =>
          workflowStateOpt.foreach(state =>
            sessionState.send(state.resultService.handleResultPagination(paginationRequest))
          )
        case modifyLogicRequest: ModifyLogicRequest =>
          if (workflowStateOpt.isDefined) {
            val executionService = workflowStateOpt.get.executionService.getValue
            val modifyLogicResponse =
              executionService.executionReconfigurationService.modifyOperatorLogic(
                modifyLogicRequest
              )
            sessionState.send(modifyLogicResponse)
          }
        case workflowExecuteRequest: WorkflowExecuteRequest =>
          if (sessionState.getUserComputingUnitAccess != PrivilegeEnum.WRITE) {
            throw new IllegalStateException("User does not have write access to the computing unit")
          }
          workflowStateOpt match {
            case Some(workflow) =>
              // The WorkflowService's own cuid, not the request's: cancel and dispose reach the
              // queue through it, so submitting under a different one would leave an entry that
              // nothing can ever release.
              val cuid = workflow.computingUnitId
              if (ComputingUnitExecutionQueue.isQueuedComputingUnit(cuid)) {
                // A public unit runs one workflow at a time. The queue starts this run itself
                // once the unit is free, and reports the wait in the meantime; do not announce
                // "Initializing" here, since the run may not be starting at all yet.
                ComputingUnitExecutionQueue
                  .forComputingUnit(cuid)
                  .submit(workflow, workflowExecuteRequest, userOpt, session.getRequestURI)
              } else {
                sessionState.send(WorkflowStateEvent("Initializing"))
                synchronized {
                  workflow.initExecutionService(
                    workflowExecuteRequest,
                    userOpt,
                    session.getRequestURI
                  )
                }
              }
            case None => throw new IllegalStateException("workflow is not initialized")
          }
        case _: WorkflowKillRequest
            if workflowStateOpt.exists(workflow =>
              ComputingUnitExecutionQueue
                .forComputingUnit(workflow.computingUnitId)
                .cancel(workflow.workflowId)
            ) =>
          // The run was still waiting its turn, so there is no execution to kill: dropping it
          // from the queue is the whole of the cancellation.
          sessionState.send(WorkflowStateEvent("Uninitialized"))
        case other =>
          workflowStateOpt.map(_.executionService.getValue) match {
            case Some(value) => value.wsInput.onNext(other, uidOpt)
            case None        => throw new IllegalStateException("workflow execution is not initialized")
          }
      }
    } catch {
      case err: Exception =>
        logger.error("error occurred in websocket", err)
        val errEvt = WorkflowFatalError(
          COMPILATION_ERROR,
          Timestamp(Instant.now),
          err.toString,
          getStackTraceWithAllCauses(err),
          "unknown operator"
        )
        if (executionStateOpt.isDefined) {
          executionStateOpt.get.executionStateStore.metadataStore.updateState { metadataStore =>
            metadataStore
              .withFatalErrors(metadataStore.fatalErrors.filter(e => e.`type` != COMPILATION_ERROR))
              .addFatalErrors(errEvt)
          }
        } else {
          sessionState.send(
            WorkflowErrorEvent(
              Seq(errEvt)
            )
          )
        }
        throw err
    }

  }
}
