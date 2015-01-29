/+ACo-
 +ACo- Copyright 2013 Google Inc.
 +ACo-
 +ACo- Licensed under the Apache License, Version 2.0 (the +ACI-License+ACI-)+ADs-
 +ACo- you may not use this file except in compliance with the License.
 +ACo- You may obtain a copy of the License at
 +ACo-
 +ACo-    http://www.apache.org/licenses/LICENSE-2.0
 +ACo-
 +ACo- Unless required by applicable law or agreed to in writing, software
 +ACo- distributed under the License is distributed on an +ACI-AS IS+ACI- BASIS,
 +ACo- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 +ACo- See the License for the specific language governing permissions and
 +ACo- limitations under the License.
 +ACo-/

package org.neoscoinj.protocols.channels+ADs-

import org.neoscoinj.core.+ACoAOw-
import org.neoscoinj.crypto.TransactionSignature+ADs-
import org.neoscoinj.script.Script+ADs-
import org.neoscoinj.script.ScriptBuilder+ADs-
import org.neoscoinj.utils.Threading+ADs-
import org.neoscoinj.wallet.AllowUnconfirmedCoinSelector+ADs-
import org.spongycastle.crypto.params.KeyParameter+ADs-
import com.google.common.annotations.VisibleForTesting+ADs-
import com.google.common.base.Throwables+ADs-
import com.google.common.collect.Lists+ADs-
import com.google.common.util.concurrent.FutureCallback+ADs-
import com.google.common.util.concurrent.Futures+ADs-
import com.google.common.util.concurrent.ListenableFuture+ADs-
import org.slf4j.Logger+ADs-
import org.slf4j.LoggerFactory+ADs-

import javax.annotation.Nullable+ADs-
import java.util.List+ADs-

import static com.google.common.base.Preconditions.+ACoAOw-

/+ACoAKg-
 +ACo- +ADw-p+AD4-A payment channel is a method of sending money to someone such that the amount of money you send can be adjusted
 +ACo- after the fact, in an efficient manner that does not require broadcasting to the network. This can be used to
 +ACo- implement micropayments or other payment schemes in which immediate settlement is not required, but zero trust
 +ACo- negotiation is. Note that this class only allows the amount of money sent to be incremented, not decremented.+ADw-/p+AD4-
 +ACo-
 +ACo- +ADw-p+AD4-This class implements the core state machine for the client side of the protocol. The server side is implemented
 +ACo- by +AHsAQA-link PaymentChannelServerState+AH0- and +AHsAQA-link PaymentChannelClientConnection+AH0- implements a network protocol
 +ACo- suitable for TCP/IP connections which moves this class through each state. We say that the party who is sending funds
 +ACo- is the +ADw-i+AD4-client+ADw-/i+AD4- or +ADw-i+AD4-initiating party+ADw-/i+AD4-. The party that is receiving the funds is the +ADw-i+AD4-server+ADw-/i+AD4- or
 +ACo- +ADw-i+AD4-receiving party+ADw-/i+AD4-. Although the underlying Neoscoin protocol is capable of more complex relationships than that,
 +ACo- this class implements only the simplest case.+ADw-/p+AD4-
 +ACo-
 +ACo- +ADw-p+AD4-A channel has an expiry parameter. If the server halts after the multi-signature contract which locks
 +ACo- up the given value is broadcast you could get stuck in a state where you've lost all the money put into the
 +ACo- contract. To avoid this, a refund transaction is agreed ahead of time but it may only be used/broadcast after
 +ACo- the expiry time. This is specified in terms of block timestamps and once the timestamp of the chain chain approaches
 +ACo- the given time (within a few hours), the channel must be closed or else the client will broadcast the refund
 +ACo- transaction and take back all the money once the expiry time is reached.+ADw-/p+AD4-
 +ACo-
 +ACo- +ADw-p+AD4-To begin, the client calls +AHsAQA-link PaymentChannelClientState+ACM-initiate()+AH0-, which moves the channel into state
 +ACo- INITIATED and creates the initial multi-sig contract and refund transaction. If the wallet has insufficient funds an
 +ACo- exception will be thrown at this point. Once this is done, call
 +ACo- +AHsAQA-link PaymentChannelClientState+ACM-getIncompleteRefundTransaction()+AH0- and pass the resultant transaction through to the
 +ACo- server. Once you have retrieved the signature, use +AHsAQA-link PaymentChannelClientState+ACM-provideRefundSignature(byte+AFsAXQ-, KeyParameter)+AH0-.
 +ACo- You must then call +AHsAQA-link PaymentChannelClientState+ACM-storeChannelInWallet(Sha256Hash)+AH0- to store the refund transaction
 +ACo- in the wallet, protecting you against a malicious server attempting to destroy all your coins. At this point, you can
 +ACo- provide the server with the multi-sig contract (via +AHsAQA-link PaymentChannelClientState+ACM-getMultisigContract()+AH0-) safely.
 +ACo- +ADw-/p+AD4-
 +ACo-/
public class PaymentChannelClientState +AHs-
    private static final Logger log +AD0- LoggerFactory.getLogger(PaymentChannelClientState.class)+ADs-
    private static final int CONFIRMATIONS+AF8-FOR+AF8-DELETE +AD0- 3+ADs-

    private final Wallet wallet+ADs-
    // Both sides need a key (private in our case, public for the server) in order to manage the multisig contract
    // and transactions that spend it.
    private final ECKey myKey, serverMultisigKey+ADs-
    // How much value (in satoshis) is locked up into the channel.
    private final Coin totalValue+ADs-
    // When the channel will automatically settle in favor of the client, if the server halts before protocol termination
    // specified in terms of block timestamps (so it can off real time by a few hours).
    private final long expiryTime+ADs-

    // The refund is a time locked transaction that spends all the money of the channel back to the client.
    private Transaction refundTx+ADs-
    private Coin refundFees+ADs-
    // The multi-sig contract locks the value of the channel up such that the agreement of both parties is required
    // to spend it.
    private Transaction multisigContract+ADs-
    private Script multisigScript+ADs-
    // How much value is currently allocated to us. Starts as being same as totalValue.
    private Coin valueToMe+ADs-

    /+ACoAKg-
     +ACo- The different logical states the channel can be in. The channel starts out as NEW, and then steps through the
     +ACo- states until it becomes finalized. The server should have already been contacted and asked for a public key
     +ACo- by the time the NEW state is reached.
     +ACo-/
    public enum State +AHs-
        NEW,
        INITIATED,
        WAITING+AF8-FOR+AF8-SIGNED+AF8-REFUND,
        SAVE+AF8-STATE+AF8-IN+AF8-WALLET,
        PROVIDE+AF8-MULTISIG+AF8-CONTRACT+AF8-TO+AF8-SERVER,
        READY,
        EXPIRED,
        CLOSED
    +AH0-
    private State state+ADs-

    // The id of this channel in the StoredPaymentChannelClientStates, or null if it is not stored
    private StoredClientChannel storedChannel+ADs-

    PaymentChannelClientState(StoredClientChannel storedClientChannel, Wallet wallet) throws VerificationException +AHs-
        // The PaymentChannelClientConnection handles storedClientChannel.active and ensures we aren't resuming channels
        this.wallet +AD0- checkNotNull(wallet)+ADs-
        this.multisigContract +AD0- checkNotNull(storedClientChannel.contract)+ADs-
        this.multisigScript +AD0- multisigContract.getOutput(0).getScriptPubKey()+ADs-
        this.refundTx +AD0- checkNotNull(storedClientChannel.refund)+ADs-
        this.refundFees +AD0- checkNotNull(storedClientChannel.refundFees)+ADs-
        this.expiryTime +AD0- refundTx.getLockTime()+ADs-
        this.myKey +AD0- checkNotNull(storedClientChannel.myKey)+ADs-
        this.serverMultisigKey +AD0- null+ADs-
        this.totalValue +AD0- multisigContract.getOutput(0).getValue()+ADs-
        this.valueToMe +AD0- checkNotNull(storedClientChannel.valueToMe)+ADs-
        this.storedChannel +AD0- storedClientChannel+ADs-
        this.state +AD0- State.READY+ADs-
        initWalletListeners()+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Returns true if the tx is a valid settlement transaction.
     +ACo-/
    public synchronized boolean isSettlementTransaction(Transaction tx) +AHs-
        try +AHs-
            tx.verify()+ADs-
            tx.getInput(0).verify(multisigContract.getOutput(0))+ADs-
            return true+ADs-
        +AH0- catch (VerificationException e) +AHs-
            return false+ADs-
        +AH0-
    +AH0-

    /+ACoAKg-
     +ACo- Creates a state object for a payment channel client. It is expected that you be ready to
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-initiate()+AH0- after construction (to avoid creating objects for channels which are
     +ACo- not going to finish opening) and thus some parameters provided here are only used in
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-initiate()+AH0- to create the Multisig contract and refund transaction.
     +ACo-
     +ACo- +AEA-param wallet a wallet that contains at least the specified amount of value.
     +ACo- +AEA-param myKey a freshly generated private key for this channel.
     +ACo- +AEA-param serverMultisigKey a public key retrieved from the server used for the initial multisig contract
     +ACo- +AEA-param value how many satoshis to put into this contract. If the channel reaches this limit, it must be closed.
     +ACo-              It is suggested you use at least +AHsAQA-link Coin+ACM-CENT+AH0- to avoid paying fees if you need to spend the refund transaction
     +ACo- +AEA-param expiryTimeInSeconds At what point (UNIX timestamp  a few hours) the channel will expire
     +ACo-
     +ACo- +AEA-throws VerificationException If either myKey's pubkey or serverMultisigKey's pubkey are non-canonical (ie invalid)
     +ACo-/
    public PaymentChannelClientState(Wallet wallet, ECKey myKey, ECKey serverMultisigKey,
                                     Coin value, long expiryTimeInSeconds) throws VerificationException +AHs-
        checkArgument(value.signum() +AD4- 0)+ADs-
        this.wallet +AD0- checkNotNull(wallet)+ADs-
        initWalletListeners()+ADs-
        this.serverMultisigKey +AD0- checkNotNull(serverMultisigKey)+ADs-
        this.myKey +AD0- checkNotNull(myKey)+ADs-
        this.valueToMe +AD0- this.totalValue +AD0- checkNotNull(value)+ADs-
        this.expiryTime +AD0- expiryTimeInSeconds+ADs-
        this.state +AD0- State.NEW+ADs-
    +AH0-

    private synchronized void initWalletListeners() +AHs-
        // Register a listener that watches out for the server closing the channel.
        if (storedChannel +ACEAPQ- null +ACYAJg- storedChannel.close +ACEAPQ- null) +AHs-
            watchCloseConfirmations()+ADs-
        +AH0-
        wallet.addEventListener(new AbstractWalletEventListener() +AHs-
            +AEA-Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) +AHs-
                synchronized (PaymentChannelClientState.this) +AHs-
                    if (multisigContract +AD0APQ- null) return+ADs-
                    if (isSettlementTransaction(tx)) +AHs-
                        log.info(+ACI-Close: transaction +AHsAfQ- closed contract +AHsAfQAi-, tx.getHash(), multisigContract.getHash())+ADs-
                        // Record the fact that it was closed along with the transaction that closed it.
                        state +AD0- State.CLOSED+ADs-
                        if (storedChannel +AD0APQ- null) return+ADs-
                        storedChannel.close +AD0- tx+ADs-
                        updateChannelInWallet()+ADs-
                        watchCloseConfirmations()+ADs-
                    +AH0-
                +AH0-
            +AH0-
        +AH0-, Threading.SAME+AF8-THREAD)+ADs-
    +AH0-

    private void watchCloseConfirmations() +AHs-
        // When we see the close transaction get a few confirmations, we can just delete the record
        // of this channel along with the refund tx from the wallet, because we're not going to need
        // any of that any more.
        final TransactionConfidence confidence +AD0- storedChannel.close.getConfidence()+ADs-
        ListenableFuture+ADw-TransactionConfidence+AD4- future +AD0- confidence.getDepthFuture(CONFIRMATIONS+AF8-FOR+AF8-DELETE, Threading.SAME+AF8-THREAD)+ADs-
        Futures.addCallback(future, new FutureCallback+ADw-TransactionConfidence+AD4-() +AHs-
            +AEA-Override
            public void onSuccess(TransactionConfidence result) +AHs-
                deleteChannelFromWallet()+ADs-
            +AH0-

            +AEA-Override
            public void onFailure(Throwable t) +AHs-
                Throwables.propagate(t)+ADs-
            +AH0-
        +AH0-)+ADs-
    +AH0-

    private synchronized void deleteChannelFromWallet() +AHs-
        log.info(+ACI-Close tx has confirmed, deleting channel from wallet: +AHsAfQAi-, storedChannel)+ADs-
        StoredPaymentChannelClientStates channels +AD0- (StoredPaymentChannelClientStates)
                wallet.getExtensions().get(StoredPaymentChannelClientStates.EXTENSION+AF8-ID)+ADs-
        channels.removeChannel(storedChannel)+ADs-
        wallet.addOrUpdateExtension(channels)+ADs-
        storedChannel +AD0- null+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- This object implements a state machine, and this accessor returns which state it's currently in.
     +ACo-/
    public synchronized State getState() +AHs-
        return state+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Creates the initial multisig contract and incomplete refund transaction which can be requested at the appropriate
     +ACo- time using +AHsAQA-link PaymentChannelClientState+ACM-getIncompleteRefundTransaction+AH0- and
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-getMultisigContract()+AH0-. The way the contract is crafted can be adjusted by
     +ACo- overriding +AHsAQA-link PaymentChannelClientState+ACM-editContractSendRequest(org.neoscoinj.core.Wallet.SendRequest)+AH0-.
     +ACo- By default unconfirmed coins are allowed to be used, as for micropayments the risk should be relatively low.
     +ACo-
     +ACo- +AEA-throws ValueOutOfRangeException if the value being used is too small to be accepted by the network
     +ACo- +AEA-throws InsufficientMoneyException if the wallet doesn't contain enough balance to initiate
     +ACo-/
    public void initiate() throws ValueOutOfRangeException, InsufficientMoneyException +AHs-
        initiate(null)+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Creates the initial multisig contract and incomplete refund transaction which can be requested at the appropriate
     +ACo- time using +AHsAQA-link PaymentChannelClientState+ACM-getIncompleteRefundTransaction+AH0- and
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-getMultisigContract()+AH0-. The way the contract is crafted can be adjusted by
     +ACo- overriding +AHsAQA-link PaymentChannelClientState+ACM-editContractSendRequest(org.neoscoinj.core.Wallet.SendRequest)+AH0-.
     +ACo- By default unconfirmed coins are allowed to be used, as for micropayments the risk should be relatively low.
     +ACo- +AEA-param userKey Key derived from a user password, needed for any signing when the wallet is encrypted.
     +ACo-                  The wallet KeyCrypter is assumed.
     +ACo-
     +ACo- +AEA-throws ValueOutOfRangeException   if the value being used is too small to be accepted by the network
     +ACo- +AEA-throws InsufficientMoneyException if the wallet doesn't contain enough balance to initiate
     +ACo-/
    public synchronized void initiate(+AEA-Nullable KeyParameter userKey) throws ValueOutOfRangeException, InsufficientMoneyException +AHs-
        final NetworkParameters params +AD0- wallet.getParams()+ADs-
        Transaction template +AD0- new Transaction(params)+ADs-
        // We always place the client key before the server key because, if either side wants some privacy, they can
        // use a fresh key for the the multisig contract and nowhere else
        List+ADw-ECKey+AD4- keys +AD0- Lists.newArrayList(myKey, serverMultisigKey)+ADs-
        // There is also probably a change output, but we don't bother shuffling them as it's obvious from the
        // format which one is the change. If we start obfuscating the change output better in future this may
        // be worth revisiting.
        TransactionOutput multisigOutput +AD0- template.addOutput(totalValue, ScriptBuilder.createMultiSigOutputScript(2, keys))+ADs-
        if (multisigOutput.getMinNonDustValue().compareTo(totalValue) +AD4- 0)
            throw new ValueOutOfRangeException(+ACI-totalValue too small to use+ACI-)+ADs-
        Wallet.SendRequest req +AD0- Wallet.SendRequest.forTx(template)+ADs-
        req.coinSelector +AD0- AllowUnconfirmedCoinSelector.get()+ADs-
        editContractSendRequest(req)+ADs-
        req.shuffleOutputs +AD0- false+ADs-   // TODO: Fix things so shuffling is usable.
        req.aesKey +AD0- userKey+ADs-
        wallet.completeTx(req)+ADs-
        Coin multisigFee +AD0- req.tx.getFee()+ADs-
        multisigContract +AD0- req.tx+ADs-
        // Build a refund transaction that protects us in the case of a bad server that's just trying to cause havoc
        // by locking up peoples money (perhaps as a precursor to a ransom attempt). We time lock it so the server
        // has an assurance that we cannot take back our money by claiming a refund before the channel closes - this
        // relies on the fact that since Neoscoin 0.8 time locked transactions are non-final. This will need to change
        // in future as it breaks the intended design of timelocking/tx replacement, but for now it simplifies this
        // specific protocol somewhat.
        refundTx +AD0- new Transaction(params)+ADs-
        refundTx.addInput(multisigOutput).setSequenceNumber(0)+ADs-   // Allow replacement when it's eventually reactivated.
        refundTx.setLockTime(expiryTime)+ADs-
        if (totalValue.compareTo(Coin.CENT) +ADw- 0) +AHs-
            // Must pay min fee.
            final Coin valueAfterFee +AD0- totalValue.subtract(Transaction.REFERENCE+AF8-DEFAULT+AF8-MIN+AF8-TX+AF8-FEE)+ADs-
            if (Transaction.MIN+AF8-NONDUST+AF8-OUTPUT.compareTo(valueAfterFee) +AD4- 0)
                throw new ValueOutOfRangeException(+ACI-totalValue too small to use+ACI-)+ADs-
            refundTx.addOutput(valueAfterFee, myKey.toAddress(params))+ADs-
            refundFees +AD0- multisigFee.add(Transaction.REFERENCE+AF8-DEFAULT+AF8-MIN+AF8-TX+AF8-FEE)+ADs-
        +AH0- else +AHs-
            refundTx.addOutput(totalValue, myKey.toAddress(params))+ADs-
            refundFees +AD0- multisigFee+ADs-
        +AH0-
        refundTx.getConfidence().setSource(TransactionConfidence.Source.SELF)+ADs-
        log.info(+ACI-initiated channel with multi-sig contract +AHsAfQ-, refund +AHsAfQAi-, multisigContract.getHashAsString(),
                refundTx.getHashAsString())+ADs-
        state +AD0- State.INITIATED+ADs-
        // Client should now call getIncompleteRefundTransaction() and send it to the server.
    +AH0-

    /+ACoAKg-
     +ACo- You can override this method in order to control the construction of the initial contract that creates the
     +ACo- channel. For example if you want it to only use specific coins, you can adjust the coin selector here.
     +ACo- The default implementation does nothing.
     +ACo-/
    protected void editContractSendRequest(Wallet.SendRequest req) +AHs-
    +AH0-

    /+ACoAKg-
     +ACo- Returns the transaction that locks the money to the agreement of both parties. Do not mutate the result.
     +ACo- Once this step is done, you can use +AHsAQA-link PaymentChannelClientState+ACM-incrementPaymentBy(Coin, KeyParameter)+AH0- to
     +ACo- start paying the server.
     +ACo-/
    public synchronized Transaction getMultisigContract() +AHs-
        checkState(multisigContract +ACEAPQ- null)+ADs-
        if (state +AD0APQ- State.PROVIDE+AF8-MULTISIG+AF8-CONTRACT+AF8-TO+AF8-SERVER)
            state +AD0- State.READY+ADs-
        return multisigContract+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Returns a partially signed (invalid) refund transaction that should be passed to the server. Once the server
     +ACo- has checked it out and provided its own signature, call
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-provideRefundSignature(byte+AFsAXQ-, KeyParameter)+AH0- with the result.
     +ACo-/
    public synchronized Transaction getIncompleteRefundTransaction() +AHs-
        checkState(refundTx +ACEAPQ- null)+ADs-
        if (state +AD0APQ- State.INITIATED)
            state +AD0- State.WAITING+AF8-FOR+AF8-SIGNED+AF8-REFUND+ADs-
        return refundTx+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- +ADw-p+AD4-When the servers signature for the refund transaction is received, call this to verify it and sign the
     +ACo- complete refund ourselves.+ADw-/p+AD4-
     +ACo-
     +ACo- +ADw-p+AD4-If this does not throw an exception, we are secure against the loss of funds and can safely provide the server
     +ACo- with the multi-sig contract to lock in the agreement. In this case, both the multisig contract and the refund
     +ACo- transaction are automatically committed to wallet so that it can handle broadcasting the refund transaction at
     +ACo- the appropriate time if necessary.+ADw-/p+AD4-
     +ACo-/
    public synchronized void provideRefundSignature(byte+AFsAXQ- theirSignature, +AEA-Nullable KeyParameter userKey)
            throws VerificationException +AHs-
        checkNotNull(theirSignature)+ADs-
        checkState(state +AD0APQ- State.WAITING+AF8-FOR+AF8-SIGNED+AF8-REFUND)+ADs-
        TransactionSignature theirSig +AD0- TransactionSignature.decodeFromNeoscoin(theirSignature, true)+ADs-
        if (theirSig.sigHashMode() +ACEAPQ- Transaction.SigHash.NONE +AHwAfA- +ACE-theirSig.anyoneCanPay())
            throw new VerificationException(+ACI-Refund signature was not SIGHASH+AF8-NONE+AHw-SIGHASH+AF8-ANYONECANPAY+ACI-)+ADs-
        // Sign the refund transaction ourselves.
        final TransactionOutput multisigContractOutput +AD0- multisigContract.getOutput(0)+ADs-
        try +AHs-
            multisigScript +AD0- multisigContractOutput.getScriptPubKey()+ADs-
        +AH0- catch (ScriptException e) +AHs-
            throw new RuntimeException(e)+ADs-  // Cannot happen: we built this ourselves.
        +AH0-
        TransactionSignature ourSignature +AD0-
                refundTx.calculateSignature(0, myKey.maybeDecrypt(userKey),
                        multisigScript, Transaction.SigHash.ALL, false)+ADs-
        // Insert the signatures.
        Script scriptSig +AD0- ScriptBuilder.createMultiSigInputScript(ourSignature, theirSig)+ADs-
        log.info(+ACI-Refund scriptSig: +AHsAfQAi-, scriptSig)+ADs-
        log.info(+ACI-Multi-sig contract scriptPubKey: +AHsAfQAi-, multisigScript)+ADs-
        TransactionInput refundInput +AD0- refundTx.getInput(0)+ADs-
        refundInput.setScriptSig(scriptSig)+ADs-
        refundInput.verify(multisigContractOutput)+ADs-
        state +AD0- State.SAVE+AF8-STATE+AF8-IN+AF8-WALLET+ADs-
    +AH0-

    private synchronized Transaction makeUnsignedChannelContract(Coin valueToMe) throws ValueOutOfRangeException +AHs-
        Transaction tx +AD0- new Transaction(wallet.getParams())+ADs-
        tx.addInput(multisigContract.getOutput(0))+ADs-
        // Our output always comes first.
        // TODO: We should drop myKey in favor of output key  multisig key separation
        // (as its always obvious who the client is based on T2 output order)
        tx.addOutput(valueToMe, myKey.toAddress(wallet.getParams()))+ADs-
        return tx+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Checks if the channel is expired, setting state to +AHsAQA-link State+ACM-EXPIRED+AH0-, removing this channel from wallet
     +ACo- storage and throwing an +AHsAQA-link IllegalStateException+AH0- if it is.
     +ACo-/
    public synchronized void checkNotExpired() +AHs-
        if (Utils.currentTimeSeconds() +AD4- expiryTime) +AHs-
            state +AD0- State.EXPIRED+ADs-
            disconnectFromChannel()+ADs-
            throw new IllegalStateException(+ACI-Channel expired+ACI-)+ADs-
        +AH0-
    +AH0-

    /+ACoAKg- Container for a signature and an amount that was sent. +ACo-/
    public static class IncrementedPayment +AHs-
        public TransactionSignature signature+ADs-
        public Coin amount+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- +ADw-p+AD4-Updates the outputs on the payment contract transaction and re-signs it. The state must be READY in order to
     +ACo- call this method. The signature that is returned should be sent to the server so it has the ability to broadcast
     +ACo- the best seen payment when the channel closes or times out.+ADw-/p+AD4-
     +ACo-
     +ACo- +ADw-p+AD4-The returned signature is over the payment transaction, which we never have a valid copy of and thus there
     +ACo- is no accessor for it on this object.+ADw-/p+AD4-
     +ACo-
     +ACo- +ADw-p+AD4-To spend the whole channel increment by +AHsAQA-link PaymentChannelClientState+ACM-getTotalValue()+AH0- -
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-getValueRefunded()+AH0APA-/p+AD4-
     +ACo-
     +ACo- +AEA-param size How many satoshis to increment the payment by (note: not the new total).
     +ACo- +AEA-throws ValueOutOfRangeException If size is negative or the channel does not have sufficient money in it to
     +ACo-                                  complete this payment.
     +ACo-/
    public synchronized IncrementedPayment incrementPaymentBy(Coin size, +AEA-Nullable KeyParameter userKey)
            throws ValueOutOfRangeException +AHs-
        checkState(state +AD0APQ- State.READY)+ADs-
        checkNotExpired()+ADs-
        checkNotNull(size)+ADs-  // Validity of size will be checked by makeUnsignedChannelContract.
        if (size.signum() +ADw- 0)
            throw new ValueOutOfRangeException(+ACI-Tried to decrement payment+ACI-)+ADs-
        Coin newValueToMe +AD0- valueToMe.subtract(size)+ADs-
        if (newValueToMe.compareTo(Transaction.MIN+AF8-NONDUST+AF8-OUTPUT) +ADw- 0 +ACYAJg- newValueToMe.signum() +AD4- 0) +AHs-
            log.info(+ACI-New value being sent back as change was smaller than minimum nondust output, sending all+ACI-)+ADs-
            size +AD0- valueToMe+ADs-
            newValueToMe +AD0- Coin.ZERO+ADs-
        +AH0-
        if (newValueToMe.signum() +ADw- 0)
            throw new ValueOutOfRangeException(+ACI-Channel has too little money to pay +ACI-  size  +ACI- satoshis+ACI-)+ADs-
        Transaction tx +AD0- makeUnsignedChannelContract(newValueToMe)+ADs-
        log.info(+ACI-Signing new payment tx +AHsAfQAi-, tx)+ADs-
        Transaction.SigHash mode+ADs-
        // If we spent all the money we put into this channel, we (by definition) don't care what the outputs are, so
        // we sign with SIGHASH+AF8-NONE to let the server do what it wants.
        if (newValueToMe.equals(Coin.ZERO))
            mode +AD0- Transaction.SigHash.NONE+ADs-
        else
            mode +AD0- Transaction.SigHash.SINGLE+ADs-
        TransactionSignature sig +AD0- tx.calculateSignature(0, myKey.maybeDecrypt(userKey), multisigScript, mode, true)+ADs-
        valueToMe +AD0- newValueToMe+ADs-
        updateChannelInWallet()+ADs-
        IncrementedPayment payment +AD0- new IncrementedPayment()+ADs-
        payment.signature +AD0- sig+ADs-
        payment.amount +AD0- size+ADs-
        return payment+ADs-
    +AH0-

    private synchronized void updateChannelInWallet() +AHs-
        if (storedChannel +AD0APQ- null)
            return+ADs-
        storedChannel.valueToMe +AD0- valueToMe+ADs-
        StoredPaymentChannelClientStates channels +AD0- (StoredPaymentChannelClientStates)
                wallet.getExtensions().get(StoredPaymentChannelClientStates.EXTENSION+AF8-ID)+ADs-
        wallet.addOrUpdateExtension(channels)+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Sets this channel's state in +AHsAQA-link StoredPaymentChannelClientStates+AH0- to unopened so this channel can be reopened
     +ACo- later.
     +ACo-
     +ACo- +AEA-see PaymentChannelClientState+ACM-storeChannelInWallet(Sha256Hash)
     +ACo-/
    public synchronized void disconnectFromChannel() +AHs-
        if (storedChannel +AD0APQ- null)
            return+ADs-
        synchronized (storedChannel) +AHs-
            storedChannel.active +AD0- false+ADs-
        +AH0-
    +AH0-

    /+ACoAKg-
     +ACo- Skips saving state in the wallet for testing
     +ACo-/
    +AEA-VisibleForTesting synchronized void fakeSave() +AHs-
        try +AHs-
            wallet.commitTx(multisigContract)+ADs-
        +AH0- catch (VerificationException e) +AHs-
            throw new RuntimeException(e)+ADs- // We created it
        +AH0-
        state +AD0- State.PROVIDE+AF8-MULTISIG+AF8-CONTRACT+AF8-TO+AF8-SERVER+ADs-
    +AH0-

    +AEA-VisibleForTesting synchronized void doStoreChannelInWallet(Sha256Hash id) +AHs-
        StoredPaymentChannelClientStates channels +AD0- (StoredPaymentChannelClientStates)
                wallet.getExtensions().get(StoredPaymentChannelClientStates.EXTENSION+AF8-ID)+ADs-
        checkNotNull(channels, +ACI-You have not added the StoredPaymentChannelClientStates extension to the wallet.+ACI-)+ADs-
        checkState(channels.getChannel(id, multisigContract.getHash()) +AD0APQ- null)+ADs-
        storedChannel +AD0- new StoredClientChannel(id, multisigContract, refundTx, myKey, valueToMe, refundFees, true)+ADs-
        channels.putChannel(storedChannel)+ADs-
        wallet.addOrUpdateExtension(channels)+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- +ADw-p+AD4-Stores this channel's state in the wallet as a part of a +AHsAQA-link StoredPaymentChannelClientStates+AH0- wallet
     +ACo- extension and keeps it up-to-date each time payment is incremented. This allows the
     +ACo- +AHsAQA-link StoredPaymentChannelClientStates+AH0- object to keep track of timeouts and broadcast the refund transaction
     +ACo- when the channel expires.+ADw-/p+AD4-
     +ACo-
     +ACo- +ADw-p+AD4-A channel may only be stored after it has fully opened (ie state +AD0APQ- State.READY). The wallet provided in the
     +ACo- constructor must already have a +AHsAQA-link StoredPaymentChannelClientStates+AH0- object in its extensions set.+ADw-/p+AD4-
     +ACo-
     +ACo- +AEA-param id A hash providing this channel with an id which uniquely identifies this server. It does not have to be
     +ACo-           unique.
     +ACo-/
    public synchronized void storeChannelInWallet(Sha256Hash id) +AHs-
        checkState(state +AD0APQ- State.SAVE+AF8-STATE+AF8-IN+AF8-WALLET +ACYAJg- id +ACEAPQ- null)+ADs-
        if (storedChannel +ACEAPQ- null) +AHs-
            checkState(storedChannel.id.equals(id))+ADs-
            return+ADs-
        +AH0-
        doStoreChannelInWallet(id)+ADs-

        try +AHs-
            wallet.commitTx(multisigContract)+ADs-
        +AH0- catch (VerificationException e) +AHs-
            throw new RuntimeException(e)+ADs- // We created it
        +AH0-
        state +AD0- State.PROVIDE+AF8-MULTISIG+AF8-CONTRACT+AF8-TO+AF8-SERVER+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Returns the fees that will be paid if the refund transaction has to be claimed because the server failed to settle
     +ACo- the channel properly. May only be called after +AHsAQA-link PaymentChannelClientState+ACM-initiate()+AH0-
     +ACo-/
    public synchronized Coin getRefundTxFees() +AHs-
        checkState(state.compareTo(State.NEW) +AD4- 0)+ADs-
        return refundFees+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Once the servers signature over the refund transaction has been received and provided using
     +ACo- +AHsAQA-link PaymentChannelClientState+ACM-provideRefundSignature(byte+AFsAXQ-, KeyParameter)+AH0- then this
     +ACo- method can be called to receive the now valid and broadcastable refund transaction.
     +ACo-/
    public synchronized Transaction getCompletedRefundTransaction() +AHs-
        checkState(state.compareTo(State.WAITING+AF8-FOR+AF8-SIGNED+AF8-REFUND) +AD4- 0)+ADs-
        return refundTx+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Gets the total value of this channel (ie the maximum payment possible)
     +ACo-/
    public Coin getTotalValue() +AHs-
        return totalValue+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Gets the current amount refunded to us from the multisig contract (ie totalValue-valueSentToServer)
     +ACo-/
    public synchronized Coin getValueRefunded() +AHs-
        checkState(state +AD0APQ- State.READY)+ADs-
        return valueToMe+ADs-
    +AH0-

    /+ACoAKg-
     +ACo- Returns the amount of money sent on this channel so far.
     +ACo-/
    public synchronized Coin getValueSpent() +AHs-
        return getTotalValue().subtract(getValueRefunded())+ADs-
    +AH0-
+AH0-
