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

/**
  * One waiting item: its owner, the round it belongs to and its arrival number.
  *
  * @param round how many runs this user already had ahead of this one when it was submitted
  * @param seq   a global arrival counter, breaking ties within a round
  */
private[service] case class FairShareEntry[K, V](
    key: K,
    uid: Int,
    round: Int,
    seq: Long,
    value: V
)

/**
  * A queue that hands the token round-robin between users rather than first-come-first-served,
  * so one user submitting a batch cannot make everybody else wait for all of it.
  *
  * Each entry is stamped once, on arrival, with the round it belongs to: the number of runs that
  * user already had in flight (waiting, plus the one they are running). Order is then simply
  * `(round, seq)` — round first so users interleave, arrival order within a round so a user's own
  * runs keep their sequence. With A submitting A1, A2 and then B submitting B1, B2:
  *
  * {{{
  *   A1 (round 0, seq 1)   B1 (round 0, seq 3)
  *   A2 (round 1, seq 2)   B2 (round 1, seq 4)
  *   => A1, B1, A2, B2
  * }}}
  *
  * Counting the *running* run in the round is what keeps that fair when submissions interleave:
  * once A1 is running, A2 arriving before B1 still gets round 1 and yields to B's first run,
  * rather than A keeping the unit simply because its own queue happened to drain.
  *
  * A stamp is never recomputed, so an entry cannot be pushed back by anything that arrives later
  * within its own round. A newly arriving user does legitimately overtake a later round, so a
  * displayed position can rise as well as fall.
  *
  * Not thread-safe: the caller holds the lock (see [[ComputingUnitExecutionQueue]]).
  */
private[service] class FairShareQueue[K, V] {

  private var seqCounter: Long = 0L
  private var pending: Vector[FairShareEntry[K, V]] = Vector.empty
  private var running: Option[(K, Int)] = None

  /** The waiting entries, in the order they will be admitted. */
  def order: Seq[FairShareEntry[K, V]] =
    pending.sortBy(entry => (entry.round, entry.seq))

  def size: Int = pending.size

  def isRunning: Boolean = running.isDefined

  def runningKey: Option[K] = running.map(_._1)

  def contains(key: K): Boolean = pending.exists(_.key == key)

  /** Whether this key is already spoken for here, either waiting or running. */
  def isKnown(key: K): Boolean = contains(key) || runningKey.contains(key)

  /** 1-based place in the admission order, or None when this key is not waiting. */
  def positionOf(key: K): Option[Int] =
    Option(order.indexWhere(_.key == key)).filter(_ >= 0).map(_ + 1)

  /**
    * Stamp and add an entry. The caller must have rejected duplicates first: two entries under
    * one key would both be admitted, and the second would clobber the first's execution.
    */
  def enqueue(key: K, uid: Int, value: V): FairShareEntry[K, V] = {
    seqCounter += 1
    val round = pending.count(_.uid == uid) + (if (running.exists(_._2 == uid)) 1 else 0)
    val entry = FairShareEntry(key, uid, round, seqCounter, value)
    pending = pending :+ entry
    entry
  }

  /** Drop a waiting entry (cancelled, or its workflow went away). */
  def remove(key: K): Option[FairShareEntry[K, V]] = {
    val found = pending.find(_.key == key)
    pending = pending.filterNot(_.key == key)
    found
  }

  /**
    * Take the next entry and mark it running. Returns None while a run still holds the token, or
    * when nothing is waiting.
    */
  def admitNext(): Option[FairShareEntry[K, V]] = {
    if (running.isDefined) return None
    order.headOption.map { entry =>
      pending = pending.filterNot(_.key == entry.key)
      running = Some((entry.key, entry.uid))
      entry
    }
  }

  /**
    * Free the token. Ignores a key that is not the one running, so a late release from a
    * previous run cannot evict its successor.
    */
  def release(key: K): Boolean =
    if (runningKey.contains(key)) {
      running = None
      true
    } else {
      false
    }
}
