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

-- Public computing units: units an administrator creates that every user may run on.
--
-- access_scope is orthogonal to `type`. `type` is the runtime kind (local / kubernetes) and a
-- public unit is still one of those; access_scope is who may use it. Overloading `type` with a
-- third value would have made "a public kubernetes unit" inexpressible.
--
-- The values are upper case, like privilege_enum and user_role_enum: jOOQ turns an enum value
-- into a Java identifier, and 'private'/'public' are Java keywords, which it would mangle into
-- private_/public_ and then serialise to the API under those names.
--
-- uid keeps its NOT NULL FK and holds the administrator who created the unit, so every existing
-- query, fetchByUid and ownership check keeps working. "Not tied to one user" is expressed by
-- access_scope alone: ComputingUnitAccess grants WRITE on a public unit to any authenticated
-- user, without a computing_unit_user_access row per user.
--
-- The type is schema-qualified because the two runners disagree about the search path: the
-- liquibase runner in sql/docker-compose.yml strips `SET search_path` out of these files before
-- applying them, while bin/local-dev.sh keeps it.

\c texera_db

SET search_path TO texera_db;

BEGIN;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'computing_unit_access_scope_enum') THEN
            CREATE TYPE texera_db.computing_unit_access_scope_enum AS ENUM ('PRIVATE', 'PUBLIC');
        END IF;
    END
$$;

ALTER TABLE texera_db.workflow_computing_unit
    ADD COLUMN IF NOT EXISTS access_scope texera_db.computing_unit_access_scope_enum
        NOT NULL DEFAULT 'PRIVATE';

-- A public unit is a shared, queued resource that users pick by name, so two live ones may not
-- share a name. Partial: terminated units keep their names, and private units are namespaced by
-- their owner rather than globally.
CREATE UNIQUE INDEX IF NOT EXISTS ux_wcu_public_name
    ON texera_db.workflow_computing_unit (name)
    WHERE access_scope = 'PUBLIC' AND terminate_time IS NULL;

COMMIT;
