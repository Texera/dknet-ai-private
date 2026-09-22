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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * The admission order of a public computing unit's queue, exercised without an engine: the
  * scheduling is pure, so the interesting cases are cheap to pin down here rather than through a
  * live execution.
  */
class FairShareQueueSpec extends AnyFlatSpec with Matchers {

  private val userA = 1
  private val userB = 2
  private val userC = 3

  private def newQueue = new FairShareQueue[String, String]

  /** Admit everything, releasing each run as soon as it starts, and report the order. */
  private def drain(queue: FairShareQueue[String, String]): List[String] = {
    Iterator
      .continually {
        val admitted = queue.admitNext()
        admitted.foreach(entry => queue.release(entry.key))
        admitted
      }
      .takeWhile(_.isDefined)
      .flatten
      .map(_.key)
      .toList
  }

  "FairShareQueue" should "interleave two users who each submit a batch" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("A2", userA, "a2")
    queue.enqueue("B1", userB, "b1")
    queue.enqueue("B2", userB, "b2")

    drain(queue) shouldBe List("A1", "B1", "A2", "B2")
  }

  it should "stay first-come-first-served for a single user" in {
    val queue = newQueue
    Seq("A1", "A2", "A3").foreach(key => queue.enqueue(key, userA, key))

    drain(queue) shouldBe List("A1", "A2", "A3")
  }

  // The point of counting the running run in the round: A's queue draining must not let A keep
  // the unit ahead of a user who has not had a turn at all.
  it should "yield to a waiting user when the running user submits again" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    val running = queue.admitNext().get
    running.key shouldBe "A1"

    queue.enqueue("A2", userA, "a2") // arrives first, but A is already running
    queue.enqueue("B1", userB, "b1")

    queue.order.map(_.key) shouldBe List("B1", "A2")
  }

  it should "give a newly arriving user the next round rather than the back of the queue" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("A2", userA, "a2")
    queue.enqueue("A3", userA, "a3")
    queue.enqueue("C1", userC, "c1")

    // C waited behind only A's first run, not the whole batch.
    queue.order.map(_.key) shouldBe List("A1", "C1", "A2", "A3")
  }

  it should "report 1-based positions and nothing for an unknown key" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("A2", userA, "a2")
    queue.enqueue("B1", userB, "b1")

    queue.positionOf("A1") shouldBe Some(1)
    queue.positionOf("B1") shouldBe Some(2)
    queue.positionOf("A2") shouldBe Some(3)
    queue.positionOf("nope") shouldBe None
  }

  it should "admit nothing while a run holds the unit" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("B1", userB, "b1")

    queue.admitNext().map(_.key) shouldBe Some("A1")
    queue.admitNext() shouldBe None
    queue.isRunning shouldBe true

    queue.release("A1") shouldBe true
    queue.admitNext().map(_.key) shouldBe Some("B1")
  }

  it should "close the queue over a cancelled entry" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("B1", userB, "b1")
    queue.enqueue("A2", userA, "a2")

    queue.remove("B1").map(_.key) shouldBe Some("B1")
    queue.contains("B1") shouldBe false
    drain(queue) shouldBe List("A1", "A2")
  }

  it should "ignore removing something that was never queued" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")

    queue.remove("B1") shouldBe None
    queue.size shouldBe 1
  }

  // A release arriving late (a watchdog firing just as the run ended, say) must not evict the
  // run that has since been admitted.
  it should "ignore a release from a run that no longer holds the unit" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("B1", userB, "b1")

    queue.admitNext().map(_.key) shouldBe Some("A1")
    queue.release("A1") shouldBe true
    queue.admitNext().map(_.key) shouldBe Some("B1")

    queue.release("A1") shouldBe false
    queue.runningKey shouldBe Some("B1")
  }

  it should "recognise a key that is queued or running" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.isKnown("A1") shouldBe true

    queue.admitNext()
    queue.contains("A1") shouldBe false
    queue.isKnown("A1") shouldBe true // now running

    queue.release("A1")
    queue.isKnown("A1") shouldBe false
  }

  // submit() distinguishes these two: something merely waiting is re-published (the caller's
  // button was stale), while something already running is a real conflict.
  it should "tell a waiting entry apart from the running one" in {
    val queue = newQueue
    queue.enqueue("A1", userA, "a1")
    queue.enqueue("A2", userA, "a2")

    queue.admitNext().map(_.key) shouldBe Some("A1")
    queue.runningKey shouldBe Some("A1")
    queue.contains("A1") shouldBe false // running, not waiting
    queue.contains("A2") shouldBe true // waiting, not running
  }

  it should "keep taking turns across many rounds" in {
    val queue = newQueue
    (1 to 3).foreach(i => queue.enqueue(s"A$i", userA, s"a$i"))
    (1 to 3).foreach(i => queue.enqueue(s"B$i", userB, s"b$i"))
    (1 to 2).foreach(i => queue.enqueue(s"C$i", userC, s"c$i"))

    drain(queue) shouldBe List("A1", "B1", "C1", "A2", "B2", "C2", "A3", "B3")
  }
}
