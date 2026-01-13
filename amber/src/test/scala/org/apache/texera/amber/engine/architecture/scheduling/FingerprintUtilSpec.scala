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

package org.apache.amber.engine.architecture.scheduling

import org.apache.amber.core.workflow.{GlobalPortIdentity, PortIdentity, WorkflowContext}
import org.apache.amber.core.workflow.cache.FingerprintUtil
import org.apache.amber.engine.e2e.TestUtils.buildWorkflow
import org.apache.amber.operator.TestOperators
import org.apache.amber.operator.aggregate.{AggregateOpDesc, AggregationFunction}
import org.apache.amber.operator.keywordSearch.KeywordSearchOpDesc
import org.apache.amber.operator.source.scan.csv.CSVScanSourceOpDesc
import org.apache.texera.workflow.LogicalLink
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FingerprintUtilSpec extends AnyFlatSpec with Matchers {

  private def newCsv(): CSVScanSourceOpDesc =
    TestOperators.headerlessSmallCsvScanOpDesc()

  private def newKeyword(
      pattern: String = "Asia",
      logicalId: Option[String] = None
  ): KeywordSearchOpDesc = {
    val op = TestOperators.keywordSearchOpDesc("column-1", pattern)
    logicalId.foreach(id => op.setOperatorId(id))
    op
  }

  private def newGroupBy(): AggregateOpDesc =
    TestOperators.aggregateAndGroupByDesc("column-1", AggregationFunction.COUNT, List[String]())

  private def buildSimpleWorkflow(csv: CSVScanSourceOpDesc, keyword: KeywordSearchOpDesc) =
    buildWorkflow(
      List(csv, keyword),
      List(
        LogicalLink(
          csv.operatorIdentifier,
          PortIdentity(0),
          keyword.operatorIdentifier,
          PortIdentity(0)
        )
      ),
      new WorkflowContext()
    )

  "FingerprintUtil" should "produce stable hash for identical workflows" in {
    val csv = newCsv()
    val keyword = newKeyword()
    val workflow = buildSimpleWorkflow(csv, keyword)
    val target = GlobalPortIdentity(
      workflow.physicalPlan.getPhysicalOpsOfLogicalOp(keyword.operatorIdentifier).head.id,
      PortIdentity(0, internal = false)
    )
    val f1 = FingerprintUtil.computeSubdagFingerprint(workflow.physicalPlan, target)
    val f2 = FingerprintUtil.computeSubdagFingerprint(workflow.physicalPlan, target)
    f1.subdagHash shouldEqual f2.subdagHash
    f1.fingerprintJson shouldEqual f2.fingerprintJson
  }

  it should "change hash when operator configuration changes" in {
    val csv = newCsv()
    val keyword = newKeyword()
    val workflow = buildSimpleWorkflow(csv, keyword)
    val target = GlobalPortIdentity(
      workflow.physicalPlan.getPhysicalOpsOfLogicalOp(keyword.operatorIdentifier).head.id,
      PortIdentity(0, internal = false)
    )
    // modify keyword search pattern by building a new operator desc
    val modifiedKeyword = newKeyword("Europe", logicalId = Some(keyword.operatorIdentifier.id))
    val modifiedWorkflow = buildWorkflow(
      List(csv, modifiedKeyword),
      List(
        LogicalLink(
          csv.operatorIdentifier,
          PortIdentity(0),
          modifiedKeyword.operatorIdentifier,
          PortIdentity(0)
        )
      ),
      new WorkflowContext()
    )
    val f1 = FingerprintUtil.computeSubdagFingerprint(workflow.physicalPlan, target)
    val f2 = FingerprintUtil.computeSubdagFingerprint(modifiedWorkflow.physicalPlan, target)
    f1.subdagHash should not equal f2.subdagHash
  }

  it should "change hash when wiring changes" in {
    val csv = newCsv()
    val keyword = newKeyword()
    val groupBy = newGroupBy()
    val workflow = buildWorkflow(
      List(csv, keyword, groupBy),
      List(
        LogicalLink(
          csv.operatorIdentifier,
          PortIdentity(0),
          keyword.operatorIdentifier,
          PortIdentity(0)
        ),
        LogicalLink(
          keyword.operatorIdentifier,
          PortIdentity(0),
          groupBy.operatorIdentifier,
          PortIdentity(0)
        )
      ),
      new WorkflowContext()
    )

    val targetKeyword = GlobalPortIdentity(
      workflow.physicalPlan.getPhysicalOpsOfLogicalOp(keyword.operatorIdentifier).head.id,
      PortIdentity(0, internal = false)
    )
    val targetGroupBy = GlobalPortIdentity(
      workflow.physicalPlan.getPhysicalOpsOfLogicalOp(groupBy.operatorIdentifier).head.id,
      PortIdentity(0, internal = false)
    )

    val fKeyword = FingerprintUtil.computeSubdagFingerprint(workflow.physicalPlan, targetKeyword)
    val fGroupBy = FingerprintUtil.computeSubdagFingerprint(workflow.physicalPlan, targetGroupBy)
    fKeyword.subdagHash should not equal fGroupBy.subdagHash
  }
}
