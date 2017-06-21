/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.server.cmdlet.executor;

import org.smartdata.server.cmdlet.CmdletFactory;
import org.smartdata.server.cmdlet.CmdletManager;
import org.smartdata.server.cmdlet.message.LaunchCmdlet;

public abstract class CmdletExecutorService {
  protected CmdletManager cmdletManager;
  protected CmdletFactory cmdletFactory;

  public CmdletExecutorService(CmdletManager cmdletManager, CmdletFactory cmdletFactory) {
    this.cmdletManager = cmdletManager;
    this.cmdletFactory = cmdletFactory;
  }

  public abstract boolean isLocalService();

  public abstract boolean canAcceptMore();

  public abstract void execute(LaunchCmdlet cmdlet);

  public abstract void stop(long cmdletId);

  public abstract void shutdown();
}