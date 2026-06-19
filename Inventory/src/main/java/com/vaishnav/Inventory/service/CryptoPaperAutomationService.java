package com.vaishnav.Inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CryptoPaperAutomationService {
    private final CryptoTradingService tradingService;

    @Value("${CRYPTO_AUTONOMOUS_PAPER_ENABLED:true}")
    private boolean enabled;

    public CryptoPaperAutomationService(CryptoTradingService tradingService) {
        this.tradingService = tradingService;
    }

    @Scheduled(fixedDelayString = "${CRYPTO_MONITOR_INTERVAL_MS:30000}", initialDelay = 15000)
    public void monitorOpenTrades() {
        if (!enabled) return;
        try { tradingService.closeRunningTrades(); } catch (Exception ignored) { }
    }

    @Scheduled(fixedDelayString = "${CRYPTO_SCAN_INTERVAL_MS:300000}", initialDelay = 30000)
    public void scanForNewTrade() {
        if (!enabled) return;
        try { tradingService.runPaperScan(); } catch (Exception ignored) { }
    }
}
