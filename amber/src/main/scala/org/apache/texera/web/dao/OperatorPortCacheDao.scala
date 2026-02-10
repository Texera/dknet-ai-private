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

package org.apache.texera.web.dao

import org.apache.texera.dao.SqlServer
import org.apache.texera.dao.jooq.generated.Tables.OPERATOR_PORT_CACHE
import org.jooq.DSLContext
import org.jooq.impl.DSL

import java.net.URI
import java.time.OffsetDateTime
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/**
  * Record representing a cache entry in the operator_port_cache table.
  *
  * @param workflowId Workflow ID
  * @param globalPortId Serialized GlobalPortIdentity
  * @param subdagHash SHA-256 hash of the upstream subDAG fingerprint
  * @param fingerprintJson Canonical JSON of the upstream subDAG
  * @param resultUri URI of the materialized output
  * @param tupleCount Number of tuples in the cached output (optional)
  * @param sourceExecutionId Execution ID that produced this cache entry (optional)
  * @param updatedAt Last update timestamp for this cache entry (optional; DB-managed).
  *                  Uses OffsetDateTime to align with Jooq's TIMESTAMPTZ mapping.
  *
  * Note: updated_at timestamp is managed by the database (DEFAULT now())
  */
case class OperatorPortCacheRecord(
    workflowId: Long,
    globalPortId: String,
    subdagHash: String,
    fingerprintJson: String,
    resultUri: URI,
    tupleCount: Option[Long],
    sourceExecutionId: Option[Long],
    updatedAt: Option[OffsetDateTime] = None
)

/**
  * Data Access Object for operator_port_cache table.
  * Provides low-level CRUD operations using Jooq.
  *
  * @param sqlServer SqlServer instance for database access
  */
class OperatorPortCacheDao(sqlServer: SqlServer) {
  private val context: DSLContext = sqlServer.createDSLContext()

  /**
    * Retrieve a cache entry by primary key (workflow_id, global_port_id, subdag_hash).
    *
    * @param workflowId Workflow ID
    * @param serializedPortId Serialized GlobalPortIdentity string
    * @param subdagHash SHA-256 hash of the upstream subDAG fingerprint
    * @return Some(OperatorPortCacheRecord) if found, None otherwise
    */
  def get(
      workflowId: Long,
      serializedPortId: String,
      subdagHash: String
  ): Option[OperatorPortCacheRecord] = {
    context
      .select(
        OPERATOR_PORT_CACHE.WORKFLOW_ID,
        OPERATOR_PORT_CACHE.GLOBAL_PORT_ID,
        OPERATOR_PORT_CACHE.SUBDAG_HASH,
        OPERATOR_PORT_CACHE.FINGERPRINT_JSON,
        OPERATOR_PORT_CACHE.RESULT_URI,
        OPERATOR_PORT_CACHE.TUPLE_COUNT,
        OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID,
        OPERATOR_PORT_CACHE.UPDATED_AT
      )
      .from(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.WORKFLOW_ID.eq(workflowId.toInt))
      .and(OPERATOR_PORT_CACHE.GLOBAL_PORT_ID.eq(serializedPortId))
      .and(OPERATOR_PORT_CACHE.SUBDAG_HASH.eq(subdagHash))
      .fetchOptional()
      .toScala
      .map { record =>
        OperatorPortCacheRecord(
          workflowId = record.value1().longValue(),
          globalPortId = record.value2(),
          subdagHash = record.value3(),
          fingerprintJson = record.value4(),
          resultUri = URI.create(record.value5()),
          tupleCount = Option(record.value6()).map(_.longValue()),
          sourceExecutionId = Option(record.value7()).map(_.longValue()),
          updatedAt = Option(record.value8())
        )
      }
  }

  /**
    * List cache entries for a workflow, ordered by most recent update.
    *
    * @param workflowId Workflow ID to list cache entries for
    * @param limit Max number of entries to return
    * @param offset Offset into the result set for pagination
    * @return Cache entries ordered by updated_at descending
    */
  def listByWorkflow(
      workflowId: Long,
      limit: Int,
      offset: Int
  ): Seq[OperatorPortCacheRecord] = {
    context
      .select(
        OPERATOR_PORT_CACHE.WORKFLOW_ID,
        OPERATOR_PORT_CACHE.GLOBAL_PORT_ID,
        OPERATOR_PORT_CACHE.SUBDAG_HASH,
        OPERATOR_PORT_CACHE.FINGERPRINT_JSON,
        OPERATOR_PORT_CACHE.RESULT_URI,
        OPERATOR_PORT_CACHE.TUPLE_COUNT,
        OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID,
        OPERATOR_PORT_CACHE.UPDATED_AT
      )
      .from(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.WORKFLOW_ID.eq(workflowId.toInt))
      .orderBy(OPERATOR_PORT_CACHE.UPDATED_AT.desc())
      .limit(limit)
      .offset(offset)
      .fetch()
      .asScala
      .map(record =>
        OperatorPortCacheRecord(
          workflowId = record.value1().longValue(),
          globalPortId = record.value2(),
          subdagHash = record.value3(),
          fingerprintJson = record.value4(),
          resultUri = URI.create(record.value5()),
          tupleCount = Option(record.value6()).map(_.longValue()),
          sourceExecutionId = Option(record.value7()).map(_.longValue()),
          updatedAt = Option(record.value8())
        )
      )
      .toSeq
  }

  /**
    * List cache entries produced by the specified source execution IDs.
    *
    * @param sourceExecutionIds execution IDs that produced cached outputs
    * @return cache entries with matching source_execution_id
    */
  def listBySourceExecutionIds(sourceExecutionIds: Seq[Long]): Seq[OperatorPortCacheRecord] = {
    val executionIds = sourceExecutionIds.distinct
    if (executionIds.isEmpty) {
      return Seq.empty
    }
    context
      .select(
        OPERATOR_PORT_CACHE.WORKFLOW_ID,
        OPERATOR_PORT_CACHE.GLOBAL_PORT_ID,
        OPERATOR_PORT_CACHE.SUBDAG_HASH,
        OPERATOR_PORT_CACHE.FINGERPRINT_JSON,
        OPERATOR_PORT_CACHE.RESULT_URI,
        OPERATOR_PORT_CACHE.TUPLE_COUNT,
        OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID,
        OPERATOR_PORT_CACHE.UPDATED_AT
      )
      .from(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID.in(executionIds.map(Long.box).asJava))
      .fetch()
      .asScala
      .map(record =>
        OperatorPortCacheRecord(
          workflowId = record.value1().longValue(),
          globalPortId = record.value2(),
          subdagHash = record.value3(),
          fingerprintJson = record.value4(),
          resultUri = URI.create(record.value5()),
          tupleCount = Option(record.value6()).map(_.longValue()),
          sourceExecutionId = Option(record.value7()).map(_.longValue()),
          updatedAt = Option(record.value8())
        )
      )
      .toSeq
  }

  /**
    * Insert or update a cache entry (upsert).
    * On conflict (workflow_id, global_port_id, subdag_hash), updates the existing record and refreshes updated_at.
    *
    * @param record OperatorPortCacheRecord to insert/update
    */
  def upsert(record: OperatorPortCacheRecord): Unit = {
    val dbRecord = context.newRecord(OPERATOR_PORT_CACHE)
    dbRecord.setWorkflowId(record.workflowId.toInt)
    dbRecord.setGlobalPortId(record.globalPortId)
    dbRecord.setSubdagHash(record.subdagHash)
    dbRecord.setFingerprintJson(record.fingerprintJson)
    dbRecord.setResultUri(record.resultUri.toString)
    record.tupleCount.foreach(c => dbRecord.setTupleCount(Long.box(c)))
    record.sourceExecutionId.foreach(eid => dbRecord.setSourceExecutionId(Long.box(eid)))

    context
      .insertInto(OPERATOR_PORT_CACHE)
      .set(dbRecord)
      .onConflict(
        OPERATOR_PORT_CACHE.WORKFLOW_ID,
        OPERATOR_PORT_CACHE.GLOBAL_PORT_ID,
        OPERATOR_PORT_CACHE.SUBDAG_HASH
      )
      .doUpdate()
      .set(OPERATOR_PORT_CACHE.RESULT_URI, dbRecord.getResultUri)
      .set(OPERATOR_PORT_CACHE.FINGERPRINT_JSON, dbRecord.getFingerprintJson)
      .set(OPERATOR_PORT_CACHE.TUPLE_COUNT, dbRecord.getTupleCount)
      .set(OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID, dbRecord.getSourceExecutionId)
      .set(OPERATOR_PORT_CACHE.UPDATED_AT, DSL.currentOffsetDateTime())
      .execute()
  }

  /**
    * Delete all cache entries for a specific workflow.
    * Useful for cache invalidation when a workflow is deleted or manually cleared.
    *
    * @param workflowId Workflow ID whose cache entries should be deleted
    */
  def deleteByWorkflow(workflowId: Long): Unit = {
    context
      .deleteFrom(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.WORKFLOW_ID.eq(workflowId.toInt))
      .execute()
  }

  /**
    * Delete cache entries by source execution IDs.
    * This removes cache metadata whose backing result artifacts were produced by
    * executions that are being cleaned up.
    *
    * @param sourceExecutionIds Execution IDs that produced the cached outputs
    * @return Number of rows deleted
    */
  def deleteBySourceExecutionIds(sourceExecutionIds: Seq[Long]): Int = {
    val executionIds = sourceExecutionIds.distinct
    if (executionIds.isEmpty) {
      return 0
    }
    context
      .deleteFrom(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.SOURCE_EXECUTION_ID.in(executionIds.map(Long.box).asJava))
      .execute()
  }

  /**
    * Delete cache entries for a workflow by global port IDs.
    * This removes all hashes for the specified output ports.
    *
    * @param workflowId Workflow ID whose cache entries should be deleted
    * @param globalPortIds Serialized GlobalPortIdentity values
    * @return Number of rows deleted
    */
  def deleteByGlobalPortIds(workflowId: Long, globalPortIds: Seq[String]): Int = {
    val distinctPorts = globalPortIds.distinct
    if (distinctPorts.isEmpty) {
      return 0
    }
    context
      .deleteFrom(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.WORKFLOW_ID.eq(workflowId.toInt))
      .and(OPERATOR_PORT_CACHE.GLOBAL_PORT_ID.in(distinctPorts.asJava))
      .execute()
  }

  /**
    * Delete cache entries for a workflow by (global_port_id, subdag_hash) pairs.
    * This removes only the specified fingerprint versions.
    *
    * @param workflowId Workflow ID whose cache entries should be deleted
    * @param portHashes Sequence of (globalPortId, subdagHash) to remove
    * @return Number of rows deleted
    */
  def deleteByGlobalPortAndHashes(
      workflowId: Long,
      portHashes: Seq[(String, String)]
  ): Int = {
    if (portHashes.isEmpty) {
      return 0
    }
    val conditions = portHashes.map { case (portId, subdagHash) =>
      OPERATOR_PORT_CACHE.GLOBAL_PORT_ID
        .eq(portId)
        .and(OPERATOR_PORT_CACHE.SUBDAG_HASH.eq(subdagHash))
    }
    context
      .deleteFrom(OPERATOR_PORT_CACHE)
      .where(OPERATOR_PORT_CACHE.WORKFLOW_ID.eq(workflowId.toInt))
      .and(DSL.or(conditions.asJava))
      .execute()
  }
}
