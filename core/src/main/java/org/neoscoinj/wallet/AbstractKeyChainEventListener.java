package org.neoscoinj.wallet;

import org.neoscoinj.core.ECKey;

import java.util.List;

public class AbstractKeyChainEventListener implements KeyChainEventListener {
    @Override
    public void onKeysAdded(List<ECKey> keys) {
    }
}
