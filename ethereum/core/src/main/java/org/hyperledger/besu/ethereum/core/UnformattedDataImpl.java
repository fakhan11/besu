/*
 *
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.hyperledger.besu.ethereum.core;

import org.hyperledger.besu.plugin.data.UnformattedData;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.DelegatingBytes;

/** Wrapper for a Bytes value to be exposed as UnformattedData. */
public class UnformattedDataImpl extends DelegatingBytes implements UnformattedData {

  public UnformattedDataImpl(final Bytes value) {
    super(value);
  }

  @Override
  public byte[] getByteArray() {
    return toArray();
  }

  @Override
  public String getHexString() {
    return toHexString();
  }
}
