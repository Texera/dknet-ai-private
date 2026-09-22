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

package org.apache.texera.amber.core.storage.model

/**
  * The file service refused this read for the user the run is acting as.
  *
  * Distinct from every other read failure because it must not be retried against the storage
  * backend directly: the fallback path uses the deployment's own credentials, which would hand
  * back exactly the bytes the refusal withheld. Readers catch the general case and fall back;
  * this one is rethrown.
  */
class FileAccessDeniedException(message: String) extends RuntimeException(message)
