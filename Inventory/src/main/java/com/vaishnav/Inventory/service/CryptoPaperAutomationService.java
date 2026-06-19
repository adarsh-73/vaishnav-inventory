package com.vaishnav.Inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CryptoPaperAutomationService {
    private final CryptoTradingService tradingService;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);
    private final AtomicLong scanCount = new AtomicLong();
    private final AtomicLong monitorCount = new AtomicLong();
    private volatile String lastScanAt = "NEVER";
    private volatile String lastMonitorAt = "NEVER";
    private volatile String lastScanResult = "WAITING_FOR_FIRST_SCAN";
    private volatile String lastError = "";

    @Value("${CRYPTO_AUTONOMOUS_PAPER_ENABLED:true}")
    private boolean enabled;

    public CryptoPaperAutomationService(CryptoTradingService tradingService) {
        this.tradingService = tradingService;
    }

    @Scheduled(fixedDelayString = "${CRYPTO_MONITOR_INTERVAL_MS:30000}", initialDelay = 15000)
    public void monitorOpenTrades() {
        if (!enabled || !monitorRunning.compareAndSet(false, true)) return;
        try {
            int checked = tradingService.closeRunningTrades().size();
            monitorCount.incrementAndGet();
            lastMonitorAt = Instant.now().toString();
            if (checked > 0) lastScanResult = "MONITORED_" + checked + "_OPEN_TRADES";
        } catch (Exception error) {
            lastError = shortError(error);
        } finally {
            monitorRunning.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${CRYPTO_SCAN_INTERVAL_MS:300000}", initialDelay = 30000)
    public void scanForNewTrade() {
        if (!enabled || !scanRunning.compareAndSet(false, true)) return;
        try {
            int opened = tradingService.runPaperScan().size();
            scanCount.incrementAndGet();
            lastScanAt = Instant.now().toString();
            lastScanResult = opened > 0 ? "OPENED_" + opened + "_PAPER_TRADE" : "SCAN_COMPLETE_NO_TRADE";
            lastError = "";
        } catch (Exception error) {
            lastError = shortError(error);
            lastScanResult = "SCAN_FAILED";
        } finally {
            scanRunning.set(false);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", enabled);
        value.put("mode", "AUTONOMOUS_PAPER_ONLY");
        value.put("scanIntervalSeconds", 300);
        value.put("monitorIntervalSeconds", 30);
        value.put("scanRunning", scanRunning.get());
        value.put("monitorRunning", monitorRunning.get());
        value.put("scanCount", scanCount.get());
        value.put("monitorCount", monitorCount.get());
        value.put("lastScanAt", lastScanAt);
        value.put("lastMonitorAt", lastMonitorAt);
        value.put("lastScanResult", lastScanResult);
        value.put("lastError", lastError);
        return value;
    }

    private String shortError(Exception error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 240 ? value.substring(0, 240) : value;
    }
}
