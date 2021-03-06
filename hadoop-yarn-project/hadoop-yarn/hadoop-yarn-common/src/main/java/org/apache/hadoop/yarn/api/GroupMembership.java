/*
 * Copyright (C) 2015 hops.io.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.api;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.io.retry.Idempotent;
import org.apache.hadoop.yarn.api.protocolrecords.LiveRMsResponse;
import org.apache.hadoop.yarn.exceptions.YarnException;

import java.io.IOException;

@Private
@Evolving
public interface GroupMembership {
  @Public
  @Evolving
  @Idempotent
  public LiveRMsResponse getLiveRMList() throws IOException, YarnException;
}
