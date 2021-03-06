/*
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
 */
package org.hyperledger.besu.ethereum.privacy;

import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.enclave.EnclaveClientException;
import org.hyperledger.besu.enclave.types.PrivacyGroup;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.debug.TraceOptions;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidator;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;
import org.hyperledger.besu.ethereum.vm.BlockHashLookup;
import org.hyperledger.besu.ethereum.vm.DebugOperationTracer;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

/*
 * Used to process transactions for priv_call.
 *
 * The processing won't affect the private world state, it is used to execute read operations on the
 * blockchain.
 */
public class PrivateTransactionSimulator {

  private static final Hash EMPTY_ROOT_HASH = Hash.wrap(MerklePatriciaTrie.EMPTY_TRIE_NODE_HASH);

  // Dummy signature for transactions to not fail being processed.
  private static final SECP256K1.Signature FAKE_SIGNATURE =
      SECP256K1.Signature.create(SECP256K1.HALF_CURVE_ORDER, SECP256K1.HALF_CURVE_ORDER, (byte) 0);

  private static final Address DEFAULT_FROM =
      Address.fromHexString("0x0000000000000000000000000000000000000000");

  private final Blockchain blockchain;
  private final WorldStateArchive worldStateArchive;
  private final ProtocolSchedule<?> protocolSchedule;
  private final PrivacyParameters privacyParameters;

  public PrivateTransactionSimulator(
      final Blockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final ProtocolSchedule<?> protocolSchedule,
      final PrivacyParameters privacyParameters) {
    this.blockchain = blockchain;
    this.worldStateArchive = worldStateArchive;
    this.protocolSchedule = protocolSchedule;
    this.privacyParameters = privacyParameters;
  }

  public Optional<PrivateTransactionProcessor.Result> process(
      final String privacyGroupId,
      final String enclaveKey,
      final CallParameter callParams,
      final Hash blockHeaderHash) {
    final BlockHeader header = blockchain.getBlockHeader(blockHeaderHash).orElse(null);
    return process(privacyGroupId, enclaveKey, callParams, header);
  }

  public Optional<PrivateTransactionProcessor.Result> process(
      final String privacyGroupId,
      final String enclaveKey,
      final CallParameter callParams,
      final long blockNumber) {
    final BlockHeader header = blockchain.getBlockHeader(blockNumber).orElse(null);
    return process(privacyGroupId, enclaveKey, callParams, header);
  }

  private Optional<PrivateTransactionProcessor.Result> process(
      final String privacyGroupIdString,
      final String enclaveKey,
      final CallParameter callParams,
      final BlockHeader header) {
    if (header == null) {
      return Optional.empty();
    }

    // check whether the user (enclaveKey) is part of the current privacy group
    PrivacyGroup privacyGroup = null;
    try {
      privacyGroup = privacyParameters.getEnclave().retrievePrivacyGroup(privacyGroupIdString);
    } catch (final EnclaveClientException e) {
      // This is thrown if the privacy group does not exist. Don't do anything. This is handled in
      // the following if.
    }
    if (privacyGroup == null || !privacyGroup.getMembers().contains(enclaveKey)) {
      return privacyGroupDoesNotExistResult();
    }

    final MutableWorldState publicWorldState =
        worldStateArchive.getMutable(header.getStateRoot()).orElse(null);
    if (publicWorldState == null) {
      return Optional.empty();
    }

    // get the last world state root hash or create a new one
    final Bytes privacyGroupId = Bytes.fromBase64String(privacyGroupIdString);
    final Hash lastRootHash =
        privacyParameters
            .getPrivateStateStorage()
            .getLatestStateRoot(privacyGroupId)
            .orElse(EMPTY_ROOT_HASH);

    final MutableWorldState disposablePrivateState =
        privacyParameters.getPrivateWorldStateArchive().getMutable(lastRootHash).get();

    final PrivateTransaction transaction =
        getPrivateTransaction(callParams, header, privacyGroupId, disposablePrivateState);

    final ProtocolSpec<?> protocolSpec = protocolSchedule.getByBlockNumber(header.getNumber());

    final PrivateTransactionProcessor privateTransactionProcessor =
        protocolSpec.getPrivateTransactionProcessor();

    final PrivateTransactionProcessor.Result result =
        privateTransactionProcessor.processTransaction(
            blockchain,
            publicWorldState.updater(),
            disposablePrivateState.updater(),
            header,
            transaction,
            protocolSpec.getMiningBeneficiaryCalculator().calculateBeneficiary(header),
            new DebugOperationTracer(TraceOptions.DEFAULT),
            new BlockHashLookup(header, blockchain),
            privacyGroupId);

    return Optional.of(result);
  }

  private PrivateTransaction getPrivateTransaction(
      final CallParameter callParams,
      final BlockHeader header,
      final Bytes privacyGroupId,
      final MutableWorldState disposablePrivateState) {
    final Address senderAddress =
        callParams.getFrom() != null ? callParams.getFrom() : DEFAULT_FROM;
    final Account sender = disposablePrivateState.get(senderAddress);
    final long nonce = sender != null ? sender.getNonce() : 0L;
    final long gasLimit =
        callParams.getGasLimit() >= 0 ? callParams.getGasLimit() : header.getGasLimit();
    final Wei gasPrice = callParams.getGasPrice() != null ? callParams.getGasPrice() : Wei.ZERO;
    final Wei value = callParams.getValue() != null ? callParams.getValue() : Wei.ZERO;
    final Bytes payload = callParams.getPayload() != null ? callParams.getPayload() : Bytes.EMPTY;

    return PrivateTransaction.builder()
        .privateFrom(Bytes.EMPTY)
        .privacyGroupId(privacyGroupId)
        .restriction(Restriction.RESTRICTED)
        .nonce(nonce)
        .gasPrice(gasPrice)
        .gasLimit(gasLimit)
        .to(callParams.getTo())
        .sender(senderAddress)
        .value(value)
        .payload(payload)
        .signature(FAKE_SIGNATURE)
        .build();
  }

  private Optional<PrivateTransactionProcessor.Result> privacyGroupDoesNotExistResult() {
    return Optional.of(
        PrivateTransactionProcessor.Result.failed(
            0L,
            ValidationResult.invalid(
                TransactionValidator.TransactionInvalidReason.PRIVACY_GROUP_DOES_NOT_EXIST),
            Optional.empty()));
  }
}
