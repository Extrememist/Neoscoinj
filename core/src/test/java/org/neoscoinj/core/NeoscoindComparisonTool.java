/*
 * Copyright 2012 Matt Corallo.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.neoscoinj.core;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import org.neoscoinj.net.NioClient;
import org.neoscoinj.params.RegTestParams;
import org.neoscoinj.store.BlockStoreException;
import org.neoscoinj.store.FullPrunedBlockStore;
import org.neoscoinj.store.H2FullPrunedBlockStore;
import org.neoscoinj.store.MemoryBlockStore;
import org.neoscoinj.utils.BlockFileLoader;
import org.neoscoinj.utils.BriefLogFormatter;
import org.neoscoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tool for comparing the blocks which are accepted/rejected by neoscoind/neoscoinj
 * It is designed to run as a testnet-in-a-box network between a single neoscoind node and neoscoinj
 * It is not an automated unit-test because it requires a bit more set-up...read comments below
 */
public class NeoscoindComparisonTool {
    private static final Logger log = LoggerFactory.getLogger(NeoscoindComparisonTool.class);

    private static NetworkParameters params;
    private static FullPrunedBlockStore store;
    private static FullPrunedBlockChain chain;
    private static Sha256Hash neoscoindChainHead;
    private static volatile InventoryMessage mostRecentInv = null;

    static class BlockWrapper {
        public Block block;
    }

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        System.out.println("USAGE: neoscoinjBlockStoreLocation runExpensiveTests(1/0) [port=18444]");
        boolean runExpensiveTests = args.length > 1 && Integer.parseInt(args[1]) == 1;

        params = RegTestParams.get();

        File blockFile = File.createTempFile("testBlocks", ".dat");
        blockFile.deleteOnExit();

        FullBlockTestGenerator generator = new FullBlockTestGenerator(params);
        final RuleList blockList = generator.getBlocksToTest(false, runExpensiveTests, blockFile);
        final Map<Sha256Hash, Block> preloadedBlocks = new HashMap<Sha256Hash, Block>();
        final Iterator<Block> blocks = new BlockFileLoader(params, Arrays.asList(blockFile));

        try {
            store = new H2FullPrunedBlockStore(params, args.length > 0 ? args[0] : "NeoscoindComparisonTool", blockList.maximumReorgBlockCount);
            ((H2FullPrunedBlockStore)store).resetStore();
            //store = new MemoryFullPrunedBlockStore(params, blockList.maximumReorgBlockCount);
            chain = new FullPrunedBlockChain(params, store);
        } catch (BlockStoreException e) {
            e.printStackTrace();
            System.exit(1);
        }

        VersionMessage ver = new VersionMessage(params, 42);
        ver.appendToSubVer("BlockAcceptanceComparisonTool", "1.1", null);
        ver.localServices = VersionMessage.NODE_NETWORK;
        final Peer neoscoind = new Peer(params, ver, new BlockChain(params, new MemoryBlockStore(params)), new PeerAddress(InetAddress.getLocalHost()));
        Preconditions.checkState(neoscoind.getVersionMessage().hasBlockChain());

        final BlockWrapper currentBlock = new BlockWrapper();

        final Set<Sha256Hash> blocksRequested = Collections.synchronizedSet(new HashSet<Sha256Hash>());
        final Set<Sha256Hash> blocksPendingSend = Collections.synchronizedSet(new HashSet<Sha256Hash>());
        final AtomicInteger unexpectedInvs = new AtomicInteger(0);
        final SettableFuture<Void> connectedFuture = SettableFuture.create();
        neoscoind.addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
                if (!peer.getPeerVersionMessage().subVer.contains("Satoshi")) {
                    System.out.println();
                    System.out.println("************************************************************************************************************************\n" +
                                       "WARNING: You appear to be using this to test an alternative implementation with full validation rules. You should go\n" +
                                       "think hard about what you're doing. Seriously, no one has gotten even close to correctly reimplementing Neoscoin\n" +
                                       "consensus rules, despite serious investment in trying. It is a huge task and the slightest difference is a huge bug.\n" +
                                       "Instead, go work on making Neoscoin Core consensus rules a shared library and use that. Seriously, you wont get it right,\n" +
                                       "and starting with this tester as a way to try to do so will simply end in pain and lost coins.\n" +
                                       "************************************************************************************************************************");
                    System.out.println();
                    System.out.println("Giving you 30 seconds to think about the above warning...");
                    Uninterruptibles.sleepUninterruptibly(30, TimeUnit.SECONDS);
                }
                log.info("neoscoind connected");
                // Make sure neoscoind has no blocks
                neoscoind.setDownloadParameters(0, false);
                neoscoind.startBlockChainDownload();
                connectedFuture.set(null);
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                log.error("neoscoind node disconnected!");
                System.exit(1);
            }

            @Override
            public Message onPreMessageReceived(Peer peer, Message m) {
                if (m instanceof HeadersMessage) {
                    if (!((HeadersMessage) m).getBlockHeaders().isEmpty()) {
                        Block b = Iterables.getLast(((HeadersMessage) m).getBlockHeaders());
                        log.info("Got header from neoscoind " + b.getHashAsString());
                        neoscoindChainHead = b.getHash();
                    } else
                        log.info("Got empty header message from neoscoind");
                    return null;
                } else if (m instanceof Block) {
                    log.error("neoscoind sent us a block it already had, make sure neoscoind has no blocks!");
                    System.exit(1);
                } else if (m instanceof GetDataMessage) {
                    for (InventoryItem item : ((GetDataMessage) m).items)
                        if (item.type == InventoryItem.Type.Block) {
                            log.info("Requested " + item.hash);
                            if (currentBlock.block.getHash().equals(item.hash))
                                neoscoind.sendMessage(currentBlock.block);
                            else {
                                Block nextBlock = preloadedBlocks.get(item.hash);
                                if (nextBlock != null)
                                    neoscoind.sendMessage(nextBlock);
                                else {
                                    blocksPendingSend.add(item.hash);
                                    log.info("...which we will not provide yet");
                                }
                            }
                            blocksRequested.add(item.hash);
                        }
                    return null;
                } else if (m instanceof GetHeadersMessage) {
                    try {
                        if (currentBlock.block == null) {
                            log.info("Got a request for a header before we had even begun processing blocks!");
                            return null;
                        }
                        LinkedList<Block> headers = new LinkedList<Block>();
                        Block it = blockList.hashHeaderMap.get(currentBlock.block.getHash());
                        while (it != null) {
                            headers.addFirst(it);
                            it = blockList.hashHeaderMap.get(it.getPrevBlockHash());
                        }
                        LinkedList<Block> sendHeaders = new LinkedList<Block>();
                        boolean found = false;
                        for (Sha256Hash hash : ((GetHeadersMessage) m).getLocator()) {
                            for (Block b : headers) {
                                if (found) {
                                    sendHeaders.addLast(b);
                                    log.info("Sending header (" + b.getPrevBlockHash() + ") -> " + b.getHash());
                                    if (b.getHash().equals(((GetHeadersMessage) m).getStopHash()))
                                        break;
                                } else if (b.getHash().equals(hash)) {
                                    log.info("Found header " + b.getHashAsString());
                                    found = true;
                                }
                            }
                            if (found)
                                break;
                        }
                        if (!found)
                            sendHeaders = headers;
                        neoscoind.sendMessage(new HeadersMessage(params, sendHeaders));
                        InventoryMessage i = new InventoryMessage(params);
                        for (Block b : sendHeaders)
                            i.addBlock(b);
                        neoscoind.sendMessage(i);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                } else if (m instanceof InventoryMessage) {
                    if (mostRecentInv != null) {
                        log.error("Got an inv when we weren't expecting one");
                        unexpectedInvs.incrementAndGet();
                    }
                    mostRecentInv = (InventoryMessage) m;
                }
                return m;
            }
        }, Threading.SAME_THREAD);

        
        neoscoindChainHead = params.getGenesisBlock().getHash();
        
        // neoscoind MUST be on localhost or we will get banned as a DoSer
        new NioClient(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), args.length > 2 ? Integer.parseInt(args[2]) : params.getPort()), neoscoind, 1000);

        connectedFuture.get();

        ArrayList<Sha256Hash> locator = new ArrayList<Sha256Hash>(1);
        locator.add(params.getGenesisBlock().getHash());
        Sha256Hash hashTo = new Sha256Hash("0000000000000000000000000000000000000000000000000000000000000000");
                
        int rulesSinceFirstFail = 0;
        for (Rule rule : blockList.list) {
            if (rule instanceof FullBlockTestGenerator.BlockAndValidity) {
                FullBlockTestGenerator.BlockAndValidity block = (FullBlockTestGenerator.BlockAndValidity) rule;
                boolean threw = false;
                Block nextBlock = preloadedBlocks.get(((FullBlockTestGenerator.BlockAndValidity) rule).blockHash);
                // Often load at least one block because sometimes we have duplicates with the same hash (b56/57)
                for (int i = 0; i < 1
                        || nextBlock == null || !nextBlock.getHash().equals(block.blockHash);
                        i++) {
                    try {
                        Block b = blocks.next();
                        Block oldBlockWithSameHash = preloadedBlocks.put(b.getHash(), b);
                        if (oldBlockWithSameHash != null && oldBlockWithSameHash.getTransactions().size() != b.getTransactions().size())
                            blocksRequested.remove(b.getHash());
                        nextBlock = preloadedBlocks.get(block.blockHash);
                    } catch (NoSuchElementException e) {
                        if (nextBlock == null || !nextBlock.getHash().equals(block.blockHash))
                            throw e;
                    }
                }
                currentBlock.block = nextBlock;
                log.info("Testing block {} {}", block.ruleName, currentBlock.block.getHash());
                try {
                    if (chain.add(nextBlock) != block.connects) {
                        log.error("ERROR: Block didn't match connects flag on block \"" + block.ruleName + "\"");
                        rulesSinceFirstFail++;
                    }
                } catch (VerificationException e) {
                    threw = true;
                    if (!block.throwsException) {
                        log.error("ERROR: Block didn't match throws flag on block \"" + block.ruleName + "\"");
                        e.printStackTrace();
                        rulesSinceFirstFail++;
                    } else if (block.connects) {
                        log.error("ERROR: Block didn't match connects flag on block \"" + block.ruleName + "\"");
                        e.printStackTrace();
                        rulesSinceFirstFail++;
                    }
                }
                if (!threw && block.throwsException) {
                    log.error("ERROR: Block didn't match throws flag on block \"" + block.ruleName + "\"");
                    rulesSinceFirstFail++;
                } else if (!chain.getChainHead().getHeader().getHash().equals(block.hashChainTipAfterBlock)) {
                    log.error("ERROR: New block head didn't match the correct value after block \"" + block.ruleName + "\"");
                    rulesSinceFirstFail++;
                } else if (chain.getChainHead().getHeight() != block.heightAfterBlock) {
                    log.error("ERROR: New block head didn't match the correct height after block " + block.ruleName);
                    rulesSinceFirstFail++;
                }

                // Shouldnt double-request
                boolean shouldntRequest = blocksRequested.contains(nextBlock.getHash());
                if (shouldntRequest)
                    blocksRequested.remove(nextBlock.getHash());
                InventoryMessage message = new InventoryMessage(params);
                message.addBlock(nextBlock);
                neoscoind.sendMessage(message);
                log.info("Sent inv with block " + nextBlock.getHashAsString());
                if (blocksPendingSend.contains(nextBlock.getHash())) {
                    neoscoind.sendMessage(nextBlock);
                    log.info("Sent full block " + nextBlock.getHashAsString());
                }
                // neoscoind doesn't request blocks inline so we can't rely on a ping for synchronization
                for (int i = 0; !shouldntRequest && !blocksRequested.contains(nextBlock.getHash()); i++) {
                    int SLEEP_TIME = 1;
                    if (i % 1000/SLEEP_TIME == 1000/SLEEP_TIME - 1)
                        log.error("neoscoind still hasn't requested block " + block.ruleName + " with hash " + nextBlock.getHash());
                    Thread.sleep(SLEEP_TIME);
                    if (i > 60000/SLEEP_TIME) {
                        log.error("neoscoind failed to request block " + block.ruleName);
                        System.exit(1);
                    }
                }
                if (shouldntRequest) {
                    Thread.sleep(100);
                    if (blocksRequested.contains(nextBlock.getHash())) {
                        log.error("ERROR: neoscoind re-requested block " + block.ruleName + " with hash " + nextBlock.getHash());
                        rulesSinceFirstFail++;
                    }
                }
                // If the block throws, we may want to get neoscoind to request the same block again
                if (block.throwsException)
                    blocksRequested.remove(nextBlock.getHash());
                //neoscoind.sendMessage(nextBlock);
                locator.clear();
                locator.add(neoscoindChainHead);
                neoscoind.sendMessage(new GetHeadersMessage(params, locator, hashTo));
                neoscoind.ping().get();
                if (!chain.getChainHead().getHeader().getHash().equals(neoscoindChainHead)) {
                    rulesSinceFirstFail++;
                    log.error("ERROR: neoscoind and neoscoinj acceptance differs on block \"" + block.ruleName + "\"");
                }
                if (block.sendOnce)
                    preloadedBlocks.remove(nextBlock.getHash());
                log.info("Block \"" + block.ruleName + "\" completed processing");
            } else if (rule instanceof MemoryPoolState) {
                MemoryPoolMessage message = new MemoryPoolMessage();
                neoscoind.sendMessage(message);
                neoscoind.ping().get();
                if (mostRecentInv == null && !((MemoryPoolState) rule).mempool.isEmpty()) {
                    log.error("ERROR: neoscoind had an empty mempool, but we expected some transactions on rule " + rule.ruleName);
                    rulesSinceFirstFail++;
                } else if (mostRecentInv != null && ((MemoryPoolState) rule).mempool.isEmpty()) {
                    log.error("ERROR: neoscoind had a non-empty mempool, but we expected an empty one on rule " + rule.ruleName);
                    rulesSinceFirstFail++;
                } else if (mostRecentInv != null) {
                    Set<InventoryItem> originalRuleSet = new HashSet<InventoryItem>(((MemoryPoolState)rule).mempool);
                    boolean matches = mostRecentInv.items.size() == ((MemoryPoolState)rule).mempool.size();
                    for (InventoryItem item : mostRecentInv.items)
                        if (!((MemoryPoolState) rule).mempool.remove(item))
                            matches = false;
                    if (matches)
                        continue;
                    log.error("neoscoind's mempool didn't match what we were expecting on rule " + rule.ruleName);
                    log.info("  neoscoind's mempool was: ");
                    for (InventoryItem item : mostRecentInv.items)
                        log.info("    " + item.hash);
                    log.info("  The expected mempool was: ");
                    for (InventoryItem item : originalRuleSet)
                        log.info("    " + item.hash);
                    rulesSinceFirstFail++;
                }
                mostRecentInv = null;
            } else if (rule instanceof UTXORule) {
                if (neoscoind.getPeerVersionMessage().isGetUTXOsSupported()) {
                    UTXORule r = (UTXORule) rule;
                    UTXOsMessage result = neoscoind.getUTXOs(r.query).get();
                    if (!result.equals(r.result)) {
                        log.error("utxo result was not what we expected.");
                        log.error("Wanted  {}", r.result);
                        log.error("but got {}", result);
                        rulesSinceFirstFail++;
                    } else {
                        log.info("Successful utxo query {}: {}", r.ruleName, result);
                    }
                }
            } else {
                throw new RuntimeException("Unknown rule");
            }
            if (rulesSinceFirstFail > 0)
                rulesSinceFirstFail++;
            if (rulesSinceFirstFail > 6)
                System.exit(1);
        }

        if (unexpectedInvs.get() > 0)
            log.error("ERROR: Got " + unexpectedInvs.get() + " unexpected invs from neoscoind");
        log.info("Done testing.");
        System.exit(rulesSinceFirstFail > 0 || unexpectedInvs.get() > 0 ? 1 : 0);
    }
}
