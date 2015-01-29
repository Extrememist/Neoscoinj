package org.neoscoinj.core;

import org.neoscoinj.store.BlockStoreException;
import org.neoscoinj.store.FullPrunedBlockStore;
import org.neoscoinj.store.H2FullPrunedBlockStore;
import org.junit.After;

import java.io.File;

/**
 * An H2 implementation of the FullPrunedBlockStoreTest
 */
public class H2FullPrunedBlockChainTest extends AbstractFullPrunedBlockChainTest {
    @After
    public void tearDown() throws Exception {
        deleteFiles();
    }

    @Override
    public FullPrunedBlockStore createStore(NetworkParameters params, int blockCount) throws BlockStoreException {
        deleteFiles();
        return new H2FullPrunedBlockStore(params, "test", blockCount);
    }

    private void deleteFiles() {
        maybeDelete("test.h2.db");
        maybeDelete("test.trace.db");
        maybeDelete("test.lock.db");
    }

    private void maybeDelete(String s) {
        new File(s).delete();
    }

    @Override
    public void resetStore(FullPrunedBlockStore store) throws BlockStoreException {
        ((H2FullPrunedBlockStore)store).resetStore();
    }
}
