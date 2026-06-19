package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import com.vaishnav.Inventory.service.CryptoExchangeService;
import com.vaishnav.Inventory.service.CryptoTradingService;
import com.vaishnav.Inventory.service.CryptoAiConsensusService;
import com.vaishnav.Inventory.service.CryptoPaperAutomationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/crypto")
@CrossOrigin(originPatterns = "*")
public class CryptoTradingController {

    @Autowired
    private CryptoTradingService cryptoTradingService;

    @Autowired
    private CryptoExchangeService cryptoExchangeService;

    @Autowired
    private CryptoAiConsensusService cryptoAiConsensusService;

    @Autowired
    private CryptoPaperAutomationService cryptoPaperAutomationService;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return cryptoTradingService.getDashboard();
    }

    @PostMapping("/paper-scan")
    public List<CryptoPaperTrade> paperScan() {
        return cryptoTradingService.runPaperScan();
    }

    @PostMapping("/close-running")
    public List<CryptoPaperTrade> closeRunning() {
        return cryptoTradingService.closeRunningTrades();
    }

    @GetMapping("/binance/status")
    public Map<String, Object> binanceStatus() {
        return cryptoExchangeService.testnetClientStatus();
    }

    @PostMapping("/binance/connect")
    public Map<String, Object> connectBinance(@RequestBody CryptoExchangeService.BinanceConnectRequest request) {
        return cryptoExchangeService.connect(request);
    }

    @PostMapping("/binance/disconnect")
    public Map<String, Object> disconnectBinance() {
        return cryptoExchangeService.disconnect();
    }

    @GetMapping("/ai/status")
    public Map<String, Object> aiStatus() {
        return cryptoAiConsensusService.providerStatus();
    }

    @PostMapping("/ai/cache/clear")
    public Map<String, Object> clearAiCache() {
        cryptoAiConsensusService.clearCache();
        return Map.of("status", "CLEARED");
    }

    @PostMapping("/ai/verify")
    public Map<String, Object> verifyAiProviders() {
        return cryptoAiConsensusService.verifyProvidersNow();
    }

    @GetMapping("/automation/status")
    public Map<String, Object> automationStatus() {
        return cryptoPaperAutomationService.status();
    }

    @DeleteMapping("/paper-history/reset")
    public Map<String, Object> resetPaperHistory() {
        return cryptoTradingService.resetOldPaperTrades();
    }
}
