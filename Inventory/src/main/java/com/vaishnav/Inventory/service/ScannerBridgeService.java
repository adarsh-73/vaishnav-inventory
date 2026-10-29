package com.vaishnav.Inventory.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class ScannerBridgeService {

    private static final long ACTIVE_WINDOW_MS = 8_000;
    private final ConcurrentHashMap<String, BridgeState> stations = new ConcurrentHashMap<>();

    public void heartbeat(String stationToken) {
        stateFor(stationToken).lastBillingHeartbeat = System.currentTimeMillis();
    }

    public boolean isBillingActive(String stationToken) {
        BridgeState state = stations.get(validate(stationToken));
        return state != null && System.currentTimeMillis() - state.lastBillingHeartbeat < ACTIVE_WINDOW_MS;
    }

    public boolean queueScanIfBillingOpen(String stationToken, String barcode) {
        BridgeState state = stateFor(stationToken);
        if (System.currentTimeMillis() - state.lastBillingHeartbeat >= ACTIVE_WINDOW_MS) return false;
        state.pendingScans.add(barcode);
        return true;
    }

    public List<String> poll(String stationToken) {
        BridgeState state = stateFor(stationToken);
        state.lastBillingHeartbeat = System.currentTimeMillis();
        List<String> scans = new ArrayList<>();
        String barcode;
        while ((barcode = state.pendingScans.poll()) != null) scans.add(barcode);
        return scans;
    }

    public void close(String stationToken) {
        BridgeState state = stations.get(validate(stationToken));
        if (state != null) {
            state.lastBillingHeartbeat = 0;
            state.pendingScans.clear();
        }
    }

    private BridgeState stateFor(String stationToken) {
        return stations.computeIfAbsent(validate(stationToken), ignored -> new BridgeState());
    }

    private String validate(String stationToken) {
        if (stationToken == null || !stationToken.matches("[A-Za-z0-9-]{8,80}")) {
            throw new IllegalArgumentException("Scanner pairing code invalid hai");
        }
        return stationToken;
    }

    private static class BridgeState {
        private volatile long lastBillingHeartbeat;
        private final ConcurrentLinkedQueue<String> pendingScans = new ConcurrentLinkedQueue<>();
    }
}
