# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os


class UserContext:
    """
    Who this worker acts as when reading datasets and models.

    The token arrives in the startup config from the JVM, which resolves it per run: a public
    computing unit is shared by everyone and so has no user of its own, and each run must
    authenticate as whoever started it rather than as whoever created the unit.

    Falls back to the USER_JWT_TOKEN process variable, which is what a private unit carries in
    its pod environment, so a worker started outside a run behaves as it always has.
    """

    _initialized = False
    USER_JWT_TOKEN = None

    @classmethod
    def initialize(cls, user_jwt_token):
        if cls._initialized:
            raise RuntimeError(
                "User context has already been initialized and cannot be modified."
            )
        cls.USER_JWT_TOKEN = user_jwt_token
        cls._initialized = True

    @classmethod
    def get_jwt_token(cls):
        """The token to authenticate file-service requests with, or None if there is none."""
        token = cls.USER_JWT_TOKEN or os.getenv("USER_JWT_TOKEN")
        return token or None

    def __new__(cls, *args, **kwargs):
        raise TypeError(f"{cls.__name__} is a static class and cannot be instantiated.")
