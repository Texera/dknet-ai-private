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

package org.apache.texera.auth

// Permission field schema definition
case class PermissionFieldSchema(
    fieldType: String, // "boolean", "number", or "string"
    possibleValues: List[Any], // List of possible values, empty list if not a category field
    defaultValue: Any, // Default value for this permission
    description: String // Human-readable description of what this permission does
)

// Permission template containing all available permissions
case class PermissionTemplate(
    permissions: Map[String, PermissionFieldSchema]
)

object UserPermission {

  // Define the permission template with all available permissions
  val permissionTemplate: PermissionTemplate = PermissionTemplate(
    permissions = Map(
      "sshToComputingUnit" -> PermissionFieldSchema(
        fieldType = "boolean",
        possibleValues = List(true, false),
        defaultValue = false,
        description = "Allow user to access SSH terminal for computing units they have access to"
      )
    )
  )
}
